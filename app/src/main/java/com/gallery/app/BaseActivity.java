package com.gallery.app;

import android.app.PendingIntent;
import android.app.RecoverableSecurityException;
import android.content.ContentResolver;
import android.content.IntentSender;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * Base of every screen: keeps the system bar
 * icons readable in day and night mode, and knows how to move media to the trash.
 */
public abstract class BaseActivity extends AppCompatActivity {
    private Runnable pendingDone;
    private final ActivityResultLauncher<IntentSenderRequest> sender = registerForActivityResult(
            new ActivityResultContracts.StartIntentSenderForResult(), r -> {
                Runnable d = pendingDone;
                pendingDone = null;
                if (r.getResultCode() != RESULT_OK) {
                    // cancelled: nothing was deleted by this request, but earlier items of a batch may have been
                    Library.reload(this);
                    return;
                }
                if (d != null) d.run();
                else Library.reload(this);
            });

    private SwipeBack swipeBack;
    private boolean groupedBg;

    private boolean translucentTheme() {
        android.util.TypedValue tv = new android.util.TypedValue();
        return getTheme().resolveAttribute(android.R.attr.windowIsTranslucent, tv, true) && tv.data != 0;
    }

    /** iOS "inset grouped" screens (settings, updates) sit on grey in day mode; the window follows so no white strip shows. */
    protected void useGroupedBackground() {
        groupedBg = true;
        // a translucent (swipe-back) screen paints its grey on the content instead, so the screen underneath can show through
        if (!translucentTheme()) {
            getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Ui.color(this, R.color.bg_group)));
        }
    }

    /**
     * iOS back swipe: drag from the screen edge to pop this screen. Call after setContentView(); the activity
     * needs the translucent PushTheme. Screens can veto it (see {@link #canSwipeBack()}).
     */
    protected void enableSwipeBack() {
        View content = findViewById(android.R.id.content);
        if (content == null) return;
        content.setBackgroundColor(Ui.color(this, groupedBg ? R.color.bg_group : R.color.bg));
        swipeBack = new SwipeBack(this, content, this::canSwipeBack);
    }

    protected boolean canSwipeBack() {
        return true;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (swipeBack != null && swipeBack.intercept(ev)) return true;
        return super.dispatchTouchEvent(ev);
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        try {
            WindowInsetsControllerCompat c = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
            boolean light = !Appearance.isNight(this);
            c.setAppearanceLightStatusBars(light);
            c.setAppearanceLightNavigationBars(light);
        } catch (Throwable ignored) {
        }
    }

    /** Runs a system confirmation (trash / delete request). {@code done} runs only if the user accepted. */
    protected void launchSender(IntentSender is, Runnable done) {
        pendingDone = done;
        try {
            sender.launch(new IntentSenderRequest.Builder(is).build());
        } catch (Exception e) {
            pendingDone = null;
            Toast.makeText(this, R.string.error, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Moves media to the system trash (Android 11+, recoverable for 30 days) or deletes it after a
     * confirmation on older versions. {@code done} runs once the items are gone.
     */
    public void trashOrDelete(final List<Media> items, final Runnable done) {
        if (items == null || items.isEmpty()) return;
        final List<Media> snapshot = new ArrayList<>(items);
        final List<Uri> uris = new ArrayList<>();
        for (Media m : snapshot) uris.add(m.uri);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (MediaStore.canManageMedia(this)) {
                trashSilently(snapshot, done);      // media management allowed: no system dialog
                return;
            }
            if (!Store.flag(this, "mm_asked")) {    // offer it once
                Store.setFlag(this, "mm_asked", true);
                new Dlg(this).setTitle(R.string.mm_title).setMessage(R.string.mm_msg)
                        .setPositiveButton(R.string.mm_allow, (d, w) -> {
                            try {
                                startActivity(new android.content.Intent(android.provider.Settings.ACTION_REQUEST_MANAGE_MEDIA,
                                        Uri.parse("package:" + getPackageName())));
                            } catch (Exception e) {
                                Toast.makeText(this, R.string.error, Toast.LENGTH_SHORT).show();
                            }
                        })
                        .setNegativeButton(R.string.mm_later, (d, w) -> systemTrash(snapshot, uris, done))
                        .show();
                return;
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            systemTrash(snapshot, uris, done);
            return;
        }
        legacyDeleteDialog(snapshot, uris, done);
    }

    /** Trashes on a worker thread with a plain update (IS_TRASHED); anything refused falls back to the system dialog. */
    private void trashSilently(final List<Media> items, final Runnable done) {
        final ContentResolver cr = getContentResolver();
        new Thread(() -> {
            final List<Media> ok = new ArrayList<>();
            final List<Media> failed = new ArrayList<>();
            for (Media m : items) {
                try {
                    android.content.ContentValues cv = new android.content.ContentValues();
                    cv.put(MediaStore.MediaColumns.IS_TRASHED, 1);
                    if (cr.update(m.uri, cv, null, null) > 0) ok.add(m);
                    else failed.add(m);
                } catch (Exception e) {
                    failed.add(m);
                }
            }
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                if (!ok.isEmpty()) {
                    Library.removeNow(this, ok);
                    if (done != null && failed.isEmpty()) done.run();
                }
                if (!failed.isEmpty()) {
                    List<Uri> u = new ArrayList<>();
                    for (Media m : failed) u.add(m.uri);
                    systemTrash(failed, u, done);
                }
            });
        }).start();
    }

    private void systemTrash(final List<Media> snapshot, final List<Uri> uris, final Runnable done) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                PendingIntent pi = MediaStore.createTrashRequest(getContentResolver(), uris, true);
                launchSender(pi.getIntentSender(), () -> {
                    Library.removeNow(this, snapshot);
                    if (done != null) done.run();
                });
            } catch (Exception e) {
                Toast.makeText(this, R.string.error, Toast.LENGTH_SHORT).show();
            }
            return;
        }
        legacyDeleteDialog(snapshot, uris, done);
    }

    private void legacyDeleteDialog(final List<Media> snapshot, final List<Uri> uris, final Runnable done) {
        new Dlg(this)
                .setTitle(getResources().getQuantityString(R.plurals.del_title, snapshot.size(), snapshot.size()))
                .setMessage(R.string.del_perm_msg)
                .setPositiveButton(R.string.delete, (d, w) ->
                        deleteLegacy(new ArrayList<>(uris), snapshot, new java.util.HashSet<Uri>(), done))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** Deletes on a worker thread (hundreds of files on the UI thread froze the app), asking permission per file when needed. */
    private void deleteLegacy(final List<Uri> queue, final List<Media> items, final java.util.Set<Uri> deleted, final Runnable done) {
        final ContentResolver cr = getContentResolver();
        new Thread(() -> {
            final List<Uri> rest = new ArrayList<>(queue);
            boolean failed = false;
            while (!rest.isEmpty()) {
                Uri u = rest.get(0);
                try {
                    cr.delete(u, null, null);
                    deleted.add(u);
                    rest.remove(0);
                } catch (SecurityException se) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && se instanceof RecoverableSecurityException) {
                        final IntentSender is = ((RecoverableSecurityException) se).getUserAction().getActionIntent().getIntentSender();
                        runOnUiThread(() -> {
                            if (isFinishing() || isDestroyed()) return;
                            launchSender(is, () -> {
                                deleted.add(rest.remove(0));
                                deleteLegacy(rest, items, deleted, done);
                            });
                        });
                        return;
                    }
                    failed = true;
                    rest.remove(0);
                } catch (Exception e) {
                    failed = true;
                    rest.remove(0);
                }
            }
            final boolean bad = failed;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                List<Media> gone = new ArrayList<>();
                for (Media m : items) if (deleted.contains(m.uri)) gone.add(m);
                Library.removeNow(this, gone);
                if (bad) Toast.makeText(this, R.string.error, Toast.LENGTH_SHORT).show();
                if (done != null && !gone.isEmpty()) done.run();
            });
        }).start();
    }
}
