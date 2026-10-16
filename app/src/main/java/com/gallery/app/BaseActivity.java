package com.gallery.app;

import android.app.PendingIntent;
import android.app.RecoverableSecurityException;
import android.content.ContentResolver;
import android.content.IntentSender;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
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
                if (r.getResultCode() != RESULT_OK) return;
                if (d != null) d.run();
                else Library.reload(this);
            });

    /** iOS "inset grouped" screens (settings, updates) sit on grey in day mode; the window follows so no white strip shows. */
    protected void useGroupedBackground() {
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Ui.color(this, R.color.bg_group)));
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
        final List<Uri> uris = new ArrayList<>();
        for (Media m : items) uris.add(m.uri);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                PendingIntent pi = MediaStore.createTrashRequest(getContentResolver(), uris, true);
                launchSender(pi.getIntentSender(), () -> {
                    Library.reload(this);
                    if (done != null) done.run();
                });
            } catch (Exception e) {
                Toast.makeText(this, R.string.error, Toast.LENGTH_SHORT).show();
            }
            return;
        }
        new Dlg(this)
                .setTitle(getResources().getQuantityString(R.plurals.del_title, items.size(), items.size()))
                .setMessage(R.string.del_perm_msg)
                .setPositiveButton(R.string.delete, (d, w) -> deleteLegacy(new ArrayList<>(uris), done))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void deleteLegacy(final List<Uri> queue, final Runnable done) {
        if (queue.isEmpty()) {
            Library.reload(this);
            if (done != null) done.run();
            return;
        }
        Uri u = queue.get(0);
        ContentResolver cr = getContentResolver();
        try {
            cr.delete(u, null, null);
            queue.remove(0);
            deleteLegacy(queue, done);
        } catch (SecurityException se) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && se instanceof RecoverableSecurityException) {
                IntentSender is = ((RecoverableSecurityException) se).getUserAction().getActionIntent().getIntentSender();
                launchSender(is, () -> {
                    queue.remove(0);
                    deleteLegacy(queue, done);
                });
            } else {
                Toast.makeText(this, R.string.error, Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, R.string.error, Toast.LENGTH_SHORT).show();
        }
    }
}
