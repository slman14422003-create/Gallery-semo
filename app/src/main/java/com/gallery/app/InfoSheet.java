package com.gallery.app;

import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.exifinterface.media.ExifInterface;

import java.io.InputStream;
import java.util.Locale;

/** Details of a photo or video: file facts, camera EXIF, codecs. */
final class InfoSheet {
    private InfoSheet() {
    }

    static void show(final BaseActivity a, final Media m) {
        final LinearLayout box = Ui.box(a);
        ScrollView sv = new ScrollView(a);
        sv.addView(box);
        row(a, box, R.string.i_name, m.name);
        row(a, box, R.string.i_date, Fmt.dateLong(m.date()));
        row(a, box, R.string.i_size, Fmt.size(m.size));
        if (m.w > 0 && m.h > 0) {
            double mp = m.w * (double) m.h / 1_000_000d;
            row(a, box, R.string.i_resolution, m.w + " × " + m.h + (m.video ? "" : String.format(Locale.US, "  ·  %.1f MP", mp)));
        }
        if (m.video && m.duration > 0) row(a, box, R.string.i_duration, Fmt.dur(m.duration));
        row(a, box, R.string.i_type, m.mime);
        row(a, box, R.string.i_album, m.bucket);
        if (!m.path.isEmpty()) row(a, box, R.string.i_path, m.path);
        new Dlg(a).setTitle(R.string.info).setView(sv).setPositiveButton(R.string.done, null).show();

        new Thread(() -> {
            final java.util.List<String[]> extra = new java.util.ArrayList<>();
            try {
                if (m.video) video(a, m, extra);
                else exif(a, m, extra);
            } catch (Throwable ignored) {
            }
            a.runOnUiThread(() -> {
                if (a.isFinishing() || a.isDestroyed()) return;
                for (String[] e : extra) row(a, box, e[0], e[1]);
            });
        }).start();
    }

    private static void exif(BaseActivity a, Media m, java.util.List<String[]> out) throws Exception {
        try (InputStream in = a.getContentResolver().openInputStream(m.uri)) {
            if (in == null) return;
            ExifInterface ex = new ExifInterface(in);
            String make = ex.getAttribute(ExifInterface.TAG_MAKE);
            String model = ex.getAttribute(ExifInterface.TAG_MODEL);
            if (make != null || model != null) {
                String cam = ((make == null ? "" : make) + " " + (model == null ? "" : model)).trim();
                out.add(new String[]{a.getString(R.string.i_camera), cam});
            }
            StringBuilder sb = new StringBuilder();
            String f = ex.getAttribute(ExifInterface.TAG_F_NUMBER);
            if (f != null) sb.append("f/").append(f).append("   ");
            String t = ex.getAttribute(ExifInterface.TAG_EXPOSURE_TIME);
            if (t != null) {
                try {
                    double d = Double.parseDouble(t);
                    sb.append(d < 1 ? "1/" + Math.round(1 / d) + " s" : d + " s").append("   ");
                } catch (Exception e) {
                    sb.append(t).append(" s   ");
                }
            }
            String iso = ex.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY);
            if (iso != null) sb.append("ISO ").append(iso).append("   ");
            String fl = ex.getAttribute(ExifInterface.TAG_FOCAL_LENGTH);
            if (fl != null) {
                try {
                    String[] p = fl.split("/");
                    double v = p.length == 2 ? Double.parseDouble(p[0]) / Double.parseDouble(p[1]) : Double.parseDouble(fl);
                    sb.append(String.format(Locale.US, "%.1f mm", v));
                } catch (Exception e) {
                    sb.append(fl).append(" mm");
                }
            }
            if (sb.toString().trim().length() > 0) out.add(new String[]{a.getString(R.string.i_exposure), sb.toString().trim()});
            double[] ll = ex.getLatLong();
            if (ll != null) {
                out.add(new String[]{a.getString(R.string.i_location), String.format(Locale.US, "%.5f, %.5f", ll[0], ll[1])});
            }
        }
    }

    private static void video(BaseActivity a, Media m, java.util.List<String[]> out) throws Exception {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(a, m.uri);
            String br = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE);
            if (br != null) {
                out.add(new String[]{a.getString(R.string.i_bitrate), String.format(Locale.US, "%.2f Mbps", Long.parseLong(br) / 1_000_000d)});
            }
            String fps = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE);
            if (fps != null) {
                try {
                    out.add(new String[]{a.getString(R.string.i_fps), String.format(Locale.US, "%.0f fps", Float.parseFloat(fps))});
                } catch (Exception ignored) {
                }
            }
        } finally {
            try {
                r.release();
            } catch (Exception ignored) {
            }
        }
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(a, m.uri, null);
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime == null) continue;
                StringBuilder sb = new StringBuilder(mime.substring(mime.indexOf('/') + 1));
                if (mime.startsWith("video/")) {
                    if (f.containsKey(MediaFormat.KEY_WIDTH)) sb.append("  ·  ").append(f.getInteger(MediaFormat.KEY_WIDTH)).append("×").append(f.getInteger(MediaFormat.KEY_HEIGHT));
                    out.add(new String[]{a.getString(R.string.i_vcodec), sb.toString()});
                } else if (mime.startsWith("audio/")) {
                    if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) sb.append("  ·  ").append(f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)).append(" ch");
                    if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sb.append("  ·  ").append(f.getInteger(MediaFormat.KEY_SAMPLE_RATE) / 1000f).append(" kHz");
                    out.add(new String[]{a.getString(R.string.i_acodec), sb.toString()});
                }
            }
        } finally {
            ex.release();
        }
    }

    private static void row(BaseActivity a, LinearLayout box, int label, String value) {
        row(a, box, a.getString(label), value);
    }

    private static void row(BaseActivity a, LinearLayout box, String label, String value) {
        if (value == null || value.isEmpty()) return;
        TextView l = new TextView(a);
        l.setText(label);
        l.setTextSize(12.5f);
        l.setTextColor(Ui.color(a, R.color.text_hint));
        l.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        l.setPadding(0, Ui.dp(a, 10), 0, 0);
        box.addView(l);
        TextView v = new TextView(a);
        v.setText(value);
        v.setTextSize(15);
        v.setTextColor(Ui.color(a, R.color.text_primary));
        v.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        v.setTextIsSelectable(true);
        box.addView(v);
    }
}
