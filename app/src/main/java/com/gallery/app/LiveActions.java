package com.gallery.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.app.SearchManager;
import android.widget.Toast;

import java.util.Locale;

final class LiveActions {
    private LiveActions() {
    }

    static void copy(Activity a, String text) {
        ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return;
        cm.setPrimaryClip(ClipData.newPlainText("text", text));
        Toast.makeText(a, R.string.ocr_copied, Toast.LENGTH_SHORT).show();
    }

    static void shareText(Activity a, String text) {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, text);
        start(a, Intent.createChooser(i, a.getString(R.string.share)));
    }

    static void search(Activity a, String text) {
        Intent i = new Intent(Intent.ACTION_WEB_SEARCH);
        i.putExtra(SearchManager.QUERY, text);
        start(a, i);
    }

    static void call(Activity a, String number) {
        start(a, new Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)));
    }

    static void sms(Activity a, String number) {
        start(a, new Intent(Intent.ACTION_SENDTO, Uri.fromParts("sms", number, null)));
    }

    static void mail(Activity a, String address) {
        start(a, new Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", address, null)));
    }

    static void open(Activity a, String url) {
        String u = url.trim();
        String low = u.toLowerCase(Locale.ROOT);
        if (!low.startsWith("http://") && !low.startsWith("https://")) {
            if (low.contains("://")) return;
            u = "https://" + u;
        }
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(u));
        i.addCategory(Intent.CATEGORY_BROWSABLE);
        start(a, i);
    }

    private static void start(Activity a, Intent i) {
        try {
            a.startActivity(i);
        } catch (Exception e) {
            Toast.makeText(a, R.string.fm_no_app, Toast.LENGTH_SHORT).show();
        }
    }
}
