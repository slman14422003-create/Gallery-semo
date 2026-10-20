package com.gallery.app;

import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** In-memory copy of the device library, favourites, and the list the viewer / player is currently showing. */
public final class Library {
    private Library() {
    }

    public interface Listener {
        void onLibraryChanged();
    }

    public static volatile List<Media> all = new ArrayList<>();
    public static volatile boolean loaded = false;
    /** Bumped whenever the library list or the favourites change, so screens can skip rebuilding when nothing happened. */
    public static volatile int version = 0;

    /** The list the viewer / player pages through, and where it starts. */
    public static List<Media> session = new ArrayList<>();
    public static int sessionIndex = 0;
    /** The photo or video handed to the editors. */
    public static Media editTarget;
    /** Set by the editors once an edited copy was saved, so the viewer that opened them can close itself. */
    public static volatile boolean savedEdit = false;

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final AtomicInteger GEN = new AtomicInteger();
    // synchronized: read from the grid's background rebuild thread (favourites filter / marks) while the
    // UI thread may be adding to or removing from it at the same time (toggling a favourite).
    private static final Set<String> FAVS = java.util.Collections.synchronizedSet(new HashSet<>());
    private static boolean favLoaded = false;
    private static boolean watching = false;

    public static void listen(Listener l) {
        if (!LISTENERS.contains(l)) LISTENERS.add(l);
    }

    public static void unlisten(Listener l) {
        LISTENERS.remove(l);
    }

    public static void reload(Context c) {
        reload(c.getApplicationContext(), 0);
    }

    private static void reload(final Context app, final int attempt) {
        final int g = GEN.incrementAndGet();
        IO.execute(() -> {
            final List<Media> r;
            try {
                r = MediaRepo.load(app);
            } catch (Throwable t) {
                if (g == GEN.get() && attempt < 3) MAIN.postDelayed(() -> reload(app, attempt + 1), 700);
                return;
            }
            if (g != GEN.get()) return;
            MAIN.post(() -> {
                all = r;
                loaded = true;
                version++;
                for (Listener l : LISTENERS) l.onLibraryChanged();
            });
        });
    }

    /** Reloads now and once more shortly after: older Android versions index a new file a moment later. */
    public static void reloadSoon(Context c) {
        final Context app = c.getApplicationContext();
        reload(app);
        MAIN.postDelayed(() -> reload(app), 1200);
    }

    /**
     * Drops items from the in-memory library and from the viewer session right away (main thread), so every
     * screen updates instantly instead of waiting for the MediaStore observer; a real reload follows to confirm.
     */
    public static void removeNow(Context c, List<Media> gone) {
        if (gone == null || gone.isEmpty()) return;
        final Set<String> keys = new HashSet<>();
        for (Media m : gone) keys.add(m.key());
        List<Media> next = new ArrayList<>(all.size());
        for (Media m : all) if (!keys.contains(m.key())) next.add(m);
        List<Media> s = new ArrayList<>(session.size());
        for (Media m : session) if (!keys.contains(m.key())) s.add(m);
        GEN.incrementAndGet();   // an older reload still running would bring the deleted items back
        all = next;
        session = s;
        sessionIndex = Math.max(0, Math.min(sessionIndex, Math.max(0, session.size() - 1)));
        version++;
        for (Listener l : LISTENERS) l.onLibraryChanged();
        final Context app = c.getApplicationContext();
        MAIN.postDelayed(() -> reload(app), 500);
    }

    /** Reloads automatically when photos are added, edited or deleted by any app. */
    public static void watch(Context c) {
        if (watching) return;
        watching = true;
        final Context app = c.getApplicationContext();
        final Runnable again = () -> reload(app);
        ContentObserver ob = new ContentObserver(MAIN) {
            @Override
            public void onChange(boolean selfChange) {
                MAIN.removeCallbacks(again);
                MAIN.postDelayed(again, 700);
            }
        };
        try {
            app.getContentResolver().registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, ob);
            app.getContentResolver().registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, ob);
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ viewer session

    public static void setSession(List<Media> list, int index) {
        session = new ArrayList<>(list);
        sessionIndex = Math.max(0, Math.min(index, Math.max(0, session.size() - 1)));
    }

    // ------------------------------------------------------------------ favourites

    private static void ensureFavs(Context c) {
        if (favLoaded) return;
        FAVS.addAll(Store.favorites(c));
        favLoaded = true;
    }

    public static boolean isFav(Context c, Media m) {
        ensureFavs(c);
        return FAVS.contains(m.key());
    }

    public static boolean toggleFav(Context c, Media m) {
        ensureFavs(c);
        boolean now;
        if (FAVS.contains(m.key())) {
            FAVS.remove(m.key());
            now = false;
        } else {
            FAVS.add(m.key());
            now = true;
        }
        Store.setFavorites(c, FAVS);
        version++;
        return now;
    }

    /** Marks every item as favourite (or clears them all when every item already is one). Returns the new state. */
    public static boolean setFavs(Context c, List<Media> items, boolean on) {
        ensureFavs(c);
        for (Media m : items) {
            if (on) FAVS.add(m.key());
            else FAVS.remove(m.key());
        }
        Store.setFavorites(c, FAVS);
        version++;
        return on;
    }

    public static boolean allFav(Context c, List<Media> items) {
        ensureFavs(c);
        if (items.isEmpty()) return false;
        for (Media m : items) if (!FAVS.contains(m.key())) return false;
        return true;
    }

    // ------------------------------------------------------------------ albums

    private static boolean isShots(String bucket) {
        String b = bucket.toLowerCase(Locale.ROOT);
        return b.contains("screenshot") || b.contains("screen shot") || b.contains("لقطات") || b.contains("screen_capture")
                || b.contains("screenrecord");
    }

    public static List<Media> itemsFor(Context c, int kind, long bucketId) {
        ensureFavs(c);
        List<Media> out = new ArrayList<>();
        for (Media m : all) {
            boolean ok;
            switch (kind) {
                case Album.FAVORITES:
                    ok = FAVS.contains(m.key());
                    break;
                case Album.VIDEOS:
                    ok = m.video;
                    break;
                case Album.SHOTS:
                    ok = isShots(m.bucket);
                    break;
                default:
                    ok = m.bucketId == bucketId;
            }
            if (ok) out.add(m);
        }
        return out;
    }

    /** Smart albums first (when they have content), then real folders: Camera, then newest first. */
    public static List<Album> albums(Context c) {
        ensureFavs(c);
        List<Media> snap = all;
        Map<Long, Album> map = new LinkedHashMap<>();
        Album fav = new Album(), vids = new Album(), shots = new Album();
        fav.kind = Album.FAVORITES;
        vids.kind = Album.VIDEOS;
        shots.kind = Album.SHOTS;
        for (Media m : snap) {
            if (FAVS.contains(m.key())) add(fav, m);
            if (m.video) add(vids, m);
            if (isShots(m.bucket)) add(shots, m);
            Album a = map.get(m.bucketId);
            if (a == null) {
                a = new Album();
                a.bucketId = m.bucketId;
                a.name = m.bucket;
                map.put(m.bucketId, a);
            }
            add(a, m);
        }
        List<Album> real = new ArrayList<>(map.values());
        java.util.Collections.sort(real, (x, y) -> {
            boolean cx = "Camera".equalsIgnoreCase(x.name), cy = "Camera".equalsIgnoreCase(y.name);
            if (cx != cy) return cx ? -1 : 1;
            return Long.compare(y.cover == null ? 0 : y.cover.date(), x.cover == null ? 0 : x.cover.date());
        });
        List<Album> out = new ArrayList<>();
        fav.name = c.getString(R.string.alb_favorites);
        vids.name = c.getString(R.string.alb_videos);
        shots.name = c.getString(R.string.alb_screenshots);
        if (fav.count > 0) out.add(fav);
        if (vids.count > 0) out.add(vids);
        if (shots.count > 0) out.add(shots);
        out.addAll(real);
        return out;
    }

    private static void add(Album a, Media m) {
        a.count++;
        if (m.video) a.videos++;
        if (a.cover == null) a.cover = m;   // list is newest first
    }
}
