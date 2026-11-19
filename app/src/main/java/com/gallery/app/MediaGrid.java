package com.gallery.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.view.HapticFeedbackConstants;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import android.widget.PopupWindow;
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
    private LinearLayout topBlock;
    private ScrollHeader scroller;
    private final ImageButton btnBack, btnA1, btnA2, btnDel;
    private final TextView pill;
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
    private boolean dragSelect;          // finger is sliding over tiles (iOS swipe-to-select)
    private int lastDragPos = -1;
    private Set<String> dragBase = new LinkedHashSet<>();
    private int dragAnchor = -1, dragLo = -1, dragHi = -1;
    private boolean dragAdd = true;
    private float dragX, dragY;
    private int scrollDir;
    private int downPos = -1;
    private float downX, downY;
    private final int touchSlop;
    private int navInsetPx;
    private LinearLayout selBar;
    private ImageButton selShare, selDel;
    private TextView selCount;
    private PopupWindow popup;
    private int selShownCount = -1;
    private final Runnable autoScroller = this::autoScrollStep;

    // state fields (were missing -> "cannot find symbol" compile errors)
    private final List<TextView> chips = new ArrayList<>();
    private int cols = 3;
    private int filter = 0;                 // 0 all, 1 photos, 2 videos, 3 favourites
    private String query = "";
    private String baseTitle = "";
    private boolean built;
    private int rebuildGen;
    private Runnable onBack, onSelectChange;
    private final Runnable applyQuery = () -> rebuild();
    private SelectionListener selListener;   // when set, the host shows the iOS-style selection bar instead of the built-in one
    private View statusScrim;
    private int topInset;

    /** Reports selection mode and the number of selected items to the screen that owns the floating selection bar. */
    public interface SelectionListener {
        void onSelection(boolean selecting, int count);
    }

    private void autoScrollStep() {
        if (!dragSelect || scrollDir == 0) return;
        rv.scrollBy(0, Math.round(scrollDir * Ui.dp(act, 14)));
        dragMoveTo(dragX, dragY);
        rv.postOnAnimation(autoScroller);
    }

    public MediaGrid(BaseActivity a, int mode) {
        super(a);
        this.act = a;
        this.mode = mode;
        setOrientation(VERTICAL);
        setBackgroundColor(Ui.color(a, R.color.bg));

        // header, search box and filter chips form one block that scrolls away together with the photos
        topBlock = new LinearLayout(a);
        topBlock.setOrientation(VERTICAL);
        header = LayoutInflater.from(a).inflate(R.layout.view_header, this, false);
        topBlock.addView(header);
        title = header.findViewById(R.id.title);
        subtitle = header.findViewById(R.id.subtitle);
        btnBack = header.findViewById(R.id.btnBack);
        btnA1 = header.findViewById(R.id.btnA1);
        btnA2 = header.findViewById(R.id.btnA2);
        btnDel = header.findViewById(R.id.btnRefresh);
        headerIcon = header.findViewById(R.id.headerIcon);
        touchSlop = ViewConfiguration.get(a).getScaledTouchSlop();
        pill = new TextView(a);
        pill.setTextSize(16f);
        pill.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        pill.setTextColor(Ui.color(a, R.color.accent));
        pill.setGravity(Gravity.CENTER);
        pill.setSingleLine(true);
        pill.setPadding(Ui.dp(a, 16), 0, Ui.dp(a, 16), 0);
        GradientDrawable pg = new GradientDrawable();
        pg.setCornerRadius(Ui.dp(a, 20));
        pg.setColor(Ui.color(a, R.color.field));
        pill.setBackground(pg);
        Ui.press(a, pill);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-2, Ui.dp(a, 40));
        pp.setMarginStart(Ui.dp(a, 8));
        ((LinearLayout) header).addView(pill, ((LinearLayout) header).indexOfChild(btnA2) + 1, pp);
        btnA1.setVisibility(GONE);
        btnA2.setVisibility(VISIBLE);

        // filter chips
        chipScroll = new HorizontalScrollView(a);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chipScroll.setOverScrollMode(OVER_SCROLL_NEVER);
        chipRow = new LinearLayout(a);
        chipRow.setOrientation(HORIZONTAL);
        chipRow.setPaddingRelative(Ui.dp(a, 16), Ui.dp(a, 6), Ui.dp(a, 16), Ui.dp(a, 10));
        chipScroll.addView(chipRow);
        topBlock.addView(chipScroll, new LinearLayout.LayoutParams(-1, -2));
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
            topBlock.addView(searchBox, topBlock.indexOfChild(chipScroll), sp);
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
        rv.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView r, int dx, int dy) {
                if (dy != 0) preloadAhead(dy > 0);
            }
        });
                rv.setItemViewCacheSize(30);
        rv.setHasFixedSize(true);
        rv.setDrawingCacheEnabled(false);
        rv.setClipToPadding(false);
        rv.setPadding(Ui.dp(a, 1), 0, Ui.dp(a, 1), Ui.dp(a, 96));   // bottom room so the last row clears the floating tab bar
        rv.setOverScrollMode(OVER_SCROLL_NEVER);
        rv.getRecycledViewPool().setMaxRecycledViews(1, 60);
        RecyclerView.ItemAnimator ia = rv.getItemAnimator();
        if (ia != null) {
            ia.setAddDuration(220);
            ia.setRemoveDuration(180);
            ia.setMoveDuration(260);
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
        body.addView(topBlock, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        // the photos run under the status bar like on iPhone; a soft fade keeps the clock and icons readable over them
        statusScrim = new View(a);
        int bgc = Ui.color(a, R.color.bg) & 0x00FFFFFF;
        statusScrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{bgc | 0xCC000000, bgc}));
        body.addView(statusScrim, new FrameLayout.LayoutParams(-1, 0, Gravity.TOP));
        scroller = new ScrollHeader(rv, topBlock, Ui.dp(a, 96));

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
                int act = e.getActionMasked();
                if (act == MotionEvent.ACTION_DOWN) {
                    downX = e.getX();
                    downY = e.getY();
                    View v = r.findChildViewUnder(downX, downY);
                    downPos = v == null ? -1 : r.getChildAdapterPosition(v);
                } else if (act == MotionEvent.ACTION_MOVE && selecting && !dragSelect && downPos >= 0 && e.getPointerCount() == 1) {
                    // while selecting, a mostly sideways drag over the tiles selects them instead of scrolling
                    float dx = Math.abs(e.getX() - downX), dy = Math.abs(e.getY() - downY);
                    if (dx > touchSlop && dx > dy * 1.3f && downPos < items.size() && items.get(downPos) instanceof Media) {
                        beginDrag(downPos, !sel.contains(((Media) items.get(downPos)).key()));
                        dragX = e.getX();
                        dragY = e.getY();
                        return true;
                    }
                }
                if (dragSelect) {
                    if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
                        endDrag();
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
                    endDrag();
                    return;
                }
                if (act != MotionEvent.ACTION_MOVE) return;
                dragX = e.getX();
                dragY = e.getY();
                float top = r.getPaddingTop() + Ui.dp(act0(), 36);
                float bottom = r.getHeight() - Ui.dp(act0(), 120);
                int dir = dragY < top ? -1 : dragY > bottom ? 1 : 0;
                if (dir != scrollDir) {
                    scrollDir = dir;
                    r.removeCallbacks(autoScroller);
                    if (dir != 0) r.postOnAnimation(autoScroller);
                }
                dragMoveTo(dragX, dragY);
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
        pill.setOnClickListener(v -> {
            if (selecting) exitSelect();
            else enterSelect();
        });
        btnA2.setOnClickListener(v -> {
            if (selecting) showSelectMenu(v);
            else showOptions(v);
        });

        selBar = new LinearLayout(a);
        selBar.setOrientation(VERTICAL);
        selBar.setBackgroundColor((Ui.color(a, R.color.bg) & 0x00FFFFFF) | 0xF2000000);
        View hair = new View(a);
        hair.setBackgroundColor(Ui.color(a, R.color.stroke_soft));
        selBar.addView(hair, new LinearLayout.LayoutParams(-1, Math.max(1, Ui.dp(a, 1) / 2)));
        LinearLayout selRow = new LinearLayout(a);
        selRow.setOrientation(HORIZONTAL);
        selRow.setGravity(Gravity.CENTER_VERTICAL);
        selShare = barButton(R.drawable.ic_share, R.string.share);
        selDel = barButton(R.drawable.ic_delete, R.string.delete);
        selCount = new TextView(a);
        selCount.setTextSize(16f);
        selCount.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        selCount.setTextColor(Ui.color(a, R.color.text_primary));
        selCount.setGravity(Gravity.CENTER);
        selCount.setSingleLine(true);
        selRow.addView(selShare, new LinearLayout.LayoutParams(Ui.dp(a, 64), Ui.dp(a, 52)));
        selRow.addView(selCount, new LinearLayout.LayoutParams(0, Ui.dp(a, 52), 1f));
        selRow.addView(selDel, new LinearLayout.LayoutParams(Ui.dp(a, 64), Ui.dp(a, 52)));
        selBar.addView(selRow, new LinearLayout.LayoutParams(-1, -2));
        selBar.setVisibility(INVISIBLE);
        body.addView(selBar, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        selShare.setOnClickListener(v -> Actions.share(act, selected()));
        selDel.setOnClickListener(v -> act.trashOrDelete(selected(), this::exitSelect));

        applyMode();
        updateHeader();
    }

    private BaseActivity act0() {
        return act;
    }

    private ImageButton barButton(int icon, int desc) {
        ImageButton b = new ImageButton(act);
        b.setImageResource(icon);
        b.setImageTintList(ColorStateList.valueOf(Ui.color(act, R.color.accent)));
        b.setScaleType(ImageView.ScaleType.CENTER);
        b.setBackground(null);
        b.setContentDescription(act.getString(desc));
        Ui.press(act, b);
        return b;
    }

    // ------------------------------------------------------------------ configuration

    /** Space for the system navigation bar under the floating tab bar (the list scrolls beneath the bar). */
    public void setBottomInset(int px) {
        navInsetPx = px;
        if (scroller != null) scroller.setBottom(Ui.dp(act, 96) + px);
        if (selBar != null) selBar.setPadding(0, 0, 0, px);
    }

    /** Height of the status bar / cutout: the header sits below it while the photos scroll up underneath it. */
    public void setTopInset(int px) {
        if (px == topInset) return;
        topInset = px;
        topBlock.setPadding(0, px, 0, 0);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) statusScrim.getLayoutParams();
        lp.height = px > 0 ? px + Ui.dp(act, 12) : 0;
        statusScrim.setLayoutParams(lp);
    }

    public void setSelectionListener(SelectionListener l) {
        selListener = l;
        if (l != null && selBar != null) selBar.setVisibility(GONE);
    }

    public int selectedCount() {
        return sel.size();
    }

    public void shareSelected() {
        Actions.share(act, selected());
    }

    public void deleteSelected() {
        act.trashOrDelete(selected(), this::exitSelect);
    }

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
        rebuild(built);
    }

    /** Re-reads favourite / selection state of the visible tiles without reloading any picture. */
    public void refreshMarks() {
        notifyWindow();
    }

    /** Rebinds only the tiles on screen and the few cached around them; every other tile binds fresh when it scrolls in. */
    private void notifyWindow() {
        if (items.isEmpty()) return;
        int first = glm.findFirstVisibleItemPosition();
        int last = glm.findLastVisibleItemPosition();
        if (first < 0 || last < 0) {
            ad.notifyItemRangeChanged(0, Math.min(items.size(), 120), "sel");
            return;
        }
        int from = Math.max(0, first - 80);
        int to = Math.min(items.size(), last + 81);
        ad.notifyItemRangeChanged(from, to - from, "sel");
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
        chipScroll.setVisibility(GONE);   // filters live in the "..." menu now
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

    private void popView(View v) {
        v.animate().cancel();
        v.setAlpha(0f);
        v.setScaleX(0.92f);
        v.setScaleY(0.92f);
        v.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200).setInterpolator(new DecelerateInterpolator(1.5f)).start();
    }

    private void updateHeader() {
        if (scroller != null) scroller.setPinned(selecting);   // selection actions stay visible
        if (selecting) {
            title.setText(R.string.select_items);
            subtitle.setVisibility(GONE);
            btnBack.setVisibility(GONE);
            headerIcon.setVisibility(GONE);
            btnA1.setVisibility(GONE);
            btnA2.setImageResource(R.drawable.ic_more);
            btnA2.setContentDescription(act.getString(R.string.view_options));
            btnDel.setVisibility(GONE);
            if (!act.getString(R.string.cancel).contentEquals(pill.getText())) {
                pill.setText(R.string.cancel);
                popView(pill);
            }
            pill.setVisibility(VISIBLE);
            updateSelBar();
            return;
        }
        subtitle.setVisibility(VISIBLE);
        btnBack.setImageResource(R.drawable.ic_back);
        btnBack.setVisibility(onBack != null ? VISIBLE : GONE);
        headerIcon.setVisibility(GONE);
        title.setText(mode == SEARCH ? act.getString(R.string.nav_search) : baseTitle.isEmpty() ? act.getString(mode == PHOTOS ? R.string.nav_library : R.string.app_name) : baseTitle);
        int photos = 0, videos = 0;
        for (Media m : shown) {
            if (m.video) videos++;
            else photos++;
        }
        subtitle.setText(act.getString(R.string.counts, photos, videos));
        btnA1.setVisibility(GONE);
        btnA2.setImageResource(R.drawable.ic_more);
        btnA2.setContentDescription(act.getString(R.string.view_options));
        btnDel.setVisibility(GONE);
        if (!act.getString(R.string.select).contentEquals(pill.getText())) {
            pill.setText(R.string.select);
            popView(pill);
        }
        pill.setVisibility(shown.isEmpty() ? INVISIBLE : VISIBLE);
        updateSelBar();
    }

    /** Bottom bar: share, "N selected", delete. Slides up while selecting. */
    private void updateSelBar() {
        if (selListener != null) {
            selListener.onSelection(selecting, sel.size());
            return;
        }
        if (selBar == null) return;
        selBar.animate().cancel();
        if (selecting) {
            boolean any = !sel.isEmpty();
            selShare.setEnabled(any);
            selDel.setEnabled(any);
            selShare.setAlpha(any ? 1f : 0.35f);
            selDel.setAlpha(any ? 1f : 0.35f);
            if (selShownCount != sel.size()) {
                boolean first = selShownCount < 0;
                selShownCount = sel.size();
                selCount.setText(any ? act.getString(R.string.n_selected, sel.size()) : act.getString(R.string.select_items));
                if (!first) {
                    selCount.animate().cancel();
                    selCount.setAlpha(0.4f);
                    selCount.animate().alpha(1f).setDuration(140).start();
                }
            }
            if (selBar.getVisibility() != VISIBLE) {
                selBar.setVisibility(VISIBLE);
                selBar.setAlpha(0f);
                selBar.setTranslationY(Ui.dp(act, 64));
                selBar.animate().alpha(1f).translationY(0f).setDuration(320)
                        .setInterpolator(new android.view.animation.OvershootInterpolator(0.8f)).start();
            }
        } else if (selBar.getVisibility() == VISIBLE) {
            selShownCount = -1;
            selBar.animate().alpha(0f).translationY(Ui.dp(act, 64)).setDuration(220)
                    .setInterpolator(new DecelerateInterpolator(1.4f))
                    .withEndAction(() -> selBar.setVisibility(INVISIBLE)).start();
        }
    }

    // ------------------------------------------------------------------ selection

    private List<Media> selected() {
        List<Media> out = new ArrayList<>();
        for (Media m : shown) if (sel.contains(m.key())) out.add(m);
        return out;
    }

    private void enterSelect() {
        if (selecting) return;
        selecting = true;
        selectChanged();
        updateHeader();
        notifyWindow();
    }

    private void exitSelect() {
        endDrag();
        selecting = false;
        sel.clear();
        selectChanged();
        updateHeader();
        notifyWindow();
    }

    private void toggle(Media m, int pos) {
        if (!sel.remove(m.key())) sel.add(m.key());
        if (pos >= 0 && pos < items.size()) ad.notifyItemChanged(pos, "sel");
        updateHeader();
    }

    private void selectAll() {
        if (sel.size() == shown.size()) sel.clear();
        else for (Media m : shown) sel.add(m.key());
        updateHeader();
        notifyWindow();
    }

    private void toggleFavSelected() {
        List<Media> l = selected();
        boolean on = !Library.allFav(act, l);
        Library.setFavs(act, l, on);
        Toast.makeText(act, on ? R.string.fav_added : R.string.fav_removed, Toast.LENGTH_SHORT).show();
        exitSelect();
        rebuild();
    }

    private void showSelectMenu(View anchor) {
        if (popup != null && popup.isShowing()) {
            popup.dismiss();
            return;
        }
        List<PullDown.Row> menu = new ArrayList<>();
        boolean all = !shown.isEmpty() && sel.size() == shown.size();
        menu.add(PullDown.action(act.getString(all ? R.string.deselect_all : R.string.select_all), R.drawable.ic_check_circle, this::selectAll));
        if (!sel.isEmpty()) menu.add(PullDown.action(act.getString(R.string.favorite), R.drawable.ic_heart, this::toggleFavSelected));
        popup = PullDown.show(act, anchor, menu);
    }

    /** Starts a swipe selection at {@code pos}: everything between the first tile and the finger is selected (or deselected). */
    private void beginDrag(int pos, boolean add) {
        dragSelect = true;
        dragAdd = add;
        dragAnchor = pos;
        dragLo = pos;
        dragHi = pos;
        lastDragPos = pos;
        dragBase = new LinkedHashSet<>(sel);
        scrollDir = 0;
        applyDragRange(pos, pos, pos, pos);
    }

    private void endDrag() {
        dragSelect = false;
        scrollDir = 0;
        lastDragPos = -1;
        downPos = -1;
        rv.removeCallbacks(autoScroller);
    }

    private void dragMoveTo(float x, float y) {
        View v = rv.findChildViewUnder(x, y);
        if (v == null) return;
        int pos = rv.getChildAdapterPosition(v);
        if (pos < 0 || pos == lastDragPos || pos >= items.size() || !(items.get(pos) instanceof Media)) return;
        lastDragPos = pos;
        int oldLo = dragLo, oldHi = dragHi;
        dragLo = Math.min(dragAnchor, pos);
        dragHi = Math.max(dragAnchor, pos);
        applyDragRange(oldLo, oldHi, dragLo, dragHi);
    }

    private void applyDragRange(int oldLo, int oldHi, int lo, int hi) {
        int from = Math.max(0, Math.min(oldLo, lo));
        int to = Math.min(items.size() - 1, Math.max(oldHi, hi));
        boolean changed = false;
        for (int i = from; i <= to; i++) {
            Object o = items.get(i);
            if (!(o instanceof Media)) continue;
            String key = ((Media) o).key();
            boolean want = i >= lo && i <= hi ? dragAdd : dragBase.contains(key);
            if (want == sel.contains(key)) continue;
            if (want) sel.add(key);
            else sel.remove(key);
            ad.notifyItemChanged(i, "sel");
            changed = true;
        }
        if (changed) {
            rv.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            updateHeader();
        }
    }

    // ------------------------------------------------------------------ view options dialog

    private void showOptions(View anchor) {
        final List<PullDown.Row> rows = new ArrayList<>();
        if (mode != SEARCH) {                                   // the filter chips moved into this menu
            int[] fn = {R.string.f_all, R.string.f_photos, R.string.f_videos, R.string.f_favorites};
            for (int i = 0; i < fn.length; i++) {
                final int f = i;
                rows.add(PullDown.choice(act.getString(fn[i]), filter == i, () -> setFilter(f)));
            }
            rows.add(PullDown.separator());
        }
        int[] gn = {R.string.opt_group_none, R.string.opt_group_day, R.string.opt_group_month};
        int g = Store.intPref(act, "group", 1);
        List<PullDown.Row> group = new ArrayList<>();
        for (int i = 0; i < gn.length; i++) {
            final int v = i;
            group.add(PullDown.choice(act.getString(gn[i]), g == i, () -> {
                Store.setIntPref(act, "group", v);
                rebuild();
            }).keepOpen());
        }
        rows.add(PullDown.sub(act.getString(R.string.opt_group), act.getString(gn[g]), group));
        int[] sn = {R.string.opt_sort_taken, R.string.opt_sort_added};
        int so = Store.intPref(act, "sort", 0);
        List<PullDown.Row> sort = new ArrayList<>();
        for (int i = 0; i < sn.length; i++) {
            final int v = i;
            sort.add(PullDown.choice(act.getString(sn[i]), so == i, () -> {
                Store.setIntPref(act, "sort", v);
                rebuild();
            }).keepOpen());
        }
        rows.add(PullDown.sub(act.getString(R.string.opt_sort), act.getString(sn[so]), sort));
        List<PullDown.Row> columns = new ArrayList<>();
        for (int c = 2; c <= 6; c++) {
            final int v = c;
            columns.add(PullDown.choice(String.valueOf(c), cols == c, () -> setCols(v)).keepOpen());
        }
        rows.add(PullDown.sub(act.getString(R.string.opt_columns), String.valueOf(cols), columns));
        if (popup != null && popup.isShowing()) popup.dismiss();
        popup = PullDown.show(act, anchor, rows);
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
            final Tile tile = new Tile(c);
            tile.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
            final RecyclerView.ViewHolder holder = new RecyclerView.ViewHolder(tile) { };
            // listeners resolve the item from the holder's CURRENT position when tapped, so a partial rebind or a
            // DiffUtil move never leaves a stale item behind; they are created once per holder, not on every bind
            tile.setOnClickListener(v -> {
                int pos = holder.getBindingAdapterPosition();
                Media cur = mediaAt(pos);
                if (cur == null) return;
                if (selecting) toggle(cur, pos);
                else open(cur, tile);
            });
            tile.setOnLongClickListener(v -> {
                int pos = holder.getBindingAdapterPosition();
                Media cur = mediaAt(pos);
                if (cur == null) return true;
                if (!selecting) enterSelect();
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                beginDrag(pos, !sel.contains(cur.key()));
                return true;
            });
            return holder;
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
            t.bind(m, tilePx(), Library.isFav(act, m));
            t.setSelection(selecting, sel.contains(m.key()), false);
        }

        private Media mediaAt(int pos) {
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

    private int lastPreload = -1;

    /** Decodes the next few rows of thumbnails in the background so they are ready before they scroll into view. */
    private void preloadAhead(boolean down) {
        int first = glm.findFirstVisibleItemPosition(), last = glm.findLastVisibleItemPosition();
        if (first < 0 || last < 0) return;
        int anchor = down ? last : first;
        if (Math.abs(anchor - lastPreload) < cols) return;        // once per row, not per pixel
        lastPreload = anchor;
        int px = tilePx();
        int n = cols * 4;
        for (int i = 1; i <= n; i++) {
            int p = down ? last + i : first - i;
            if (p < 0 || p >= items.size()) break;
            Object o = items.get(p);
            if (o instanceof Media) Thumbs.preload(act, (Media) o, px);
        }
    }

    private int tilePx() {
        return Math.max(120, getResources().getDisplayMetrics().widthPixels / Math.max(1, cols));
    }

    private Rect tileRect(Tile t) {
        int[] o = new int[2];
        t.thumb.getLocationOnScreen(o);
        return new Rect(o[0], o[1], o[0] + t.thumb.getWidth(), o[1] + t.thumb.getHeight());
    }

    /** Screen rectangle of the visible tile that shows {@code key}; the viewer shrinks the picture back into it. */
    private Rect locate(String key) {
        if (!isAttachedToWindow()) return null;
        Rect vis = new Rect();
        if (!rv.getGlobalVisibleRect(vis)) return null;
        for (int i = 0; i < rv.getChildCount(); i++) {
            View c = rv.getChildAt(i);
            if (!(c instanceof Tile)) continue;
            int pos = rv.getChildViewHolder(c).getBindingAdapterPosition();
            if (pos < 0 || pos >= items.size() || !(items.get(pos) instanceof Media)) continue;
            if (!((Media) items.get(pos)).key().equals(key)) continue;
            Rect r = tileRect((Tile) c);
            int cx = r.centerX(), cy = r.centerY();
            return vis.contains(cx, cy) ? r : null;
        }
        return null;
    }

    private void open(Media m, Tile tile) {
        int idx = -1;
        for (int i = 0; i < shown.size(); i++) {
            if (shown.get(i).key().equals(m.key())) {
                idx = i;
                break;
            }
        }
        if (idx < 0) return;
        Hero.set(m.key(), tile != null ? tileRect(tile) : null, tilePx(), this::locate);
        Actions.openViewer(act, shown, idx);
    }
}
