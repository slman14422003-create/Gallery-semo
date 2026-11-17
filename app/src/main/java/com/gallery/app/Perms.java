package com.gallery.app;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import java.io.File;

/** Media access (photos and videos) and installing our own updates. */
public final class Perms {
    private Perms() {
    }

    /** The runtime permissions to request, depending on the Android version. */
    public static String[] mediaPerms() {
        if (Build.VERSION.SDK_INT >= 34) {
            return new String[]{Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED, Manifest.permission.ACCESS_MEDIA_LOCATION};
        }
        if (Build.VERSION.SDK_INT >= 33) {
            return new String[]{Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.ACCESS_MEDIA_LOCATION};
        }
        if (Build.VERSION.SDK_INT >= 29) {
            return new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.ACCESS_MEDIA_LOCATION};
        }
        return new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE};
    }

    private static boolean granted(Context c, String p) {
        return ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED;
    }

    /** True when at least some photos / videos can be read (full access or the "selected photos" choice). */
    public static boolean hasMedia(Context c) {
        if (Build.VERSION.SDK_INT >= 33) {
            if (granted(c, Manifest.permission.READ_MEDIA_IMAGES) || granted(c, Manifest.permission.READ_MEDIA_VIDEO)) return true;
            return Build.VERSION.SDK_INT >= 34 && granted(c, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED);
        }
        return granted(c, Manifest.permission.READ_EXTERNAL_STORAGE);
    }

    /** True when the user only shared a few items with the app (Android 14 "selected photos"). */
    public static boolean isPartial(Context c) {
        return Build.VERSION.SDK_INT >= 34
                && !granted(c, Manifest.permission.READ_MEDIA_IMAGES)
                && granted(c, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED);
    }

    public static void openAppSettings(Activity a) {
        try {
            a.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + a.getPackageName())));
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ installing our own update

    public static boolean canInstall(Context c) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O || c.getPackageManager().canRequestPackageInstalls();
    }

    public static void requestInstall(Activity a) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + a.getPackageName())));
                return;
            } catch (Exception ignored) {
            }
        }
        openAppSettings(a);
    }

    public static void installApk(final Activity a, final File apk) {
        if (!canInstall(a)) {
            new Dlg(a)
                    .setTitle(R.string.perm_install_title)
                    .setMessage(R.string.perm_install_dialog)
                    .setPositiveButton(R.string.perm_open_settings, (d, w) -> requestInstall(a))
                    .setNegativeButton(R.string.cancel, null)
                    .show();
            return;
        }
        try {
            Uri uri = FileProvider.getUriForFile(a, a.getPackageName() + ".files", apk);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            a.startActivity(i);
        } catch (Exception e) {
            android.widget.Toast.makeText(a, R.string.install_failed, android.widget.Toast.LENGTH_LONG).show();
        }
    }
}
