package com.gallery.app;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import androidx.exifinterface.media.ExifInterface;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Loading bitmaps at a safe size and saving edited copies into the gallery. */
final class MediaSaver {
    private MediaSaver() {
    }

    /** Decodes the picture so its longest side is at most {@code maxSide}, honouring the EXIF rotation. */
    static Bitmap load(Context c, Uri uri, int maxSide) throws Exception {
        if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.Source src = ImageDecoder.createSource(c.getContentResolver(), uri);
            return ImageDecoder.decodeBitmap(src, (decoder, info, s) -> {
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                int w = info.getSize().getWidth(), h = info.getSize().getHeight();
                float k = Math.min(1f, (float) maxSide / Math.max(w, h));
                if (k < 1f) decoder.setTargetSize(Math.max(1, Math.round(w * k)), Math.max(1, Math.round(h * k)));
            });
        }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, o);
        }
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / sample > maxSide) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        Bitmap b;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            b = BitmapFactory.decodeStream(in, null, o);
        }
        if (b == null) throw new java.io.IOException("decode");
        int rot = 0;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            if (in != null) {
                int ori = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1);
                rot = ori == 6 ? 90 : ori == 3 ? 180 : ori == 8 ? 270 : 0;
            }
        } catch (Exception ignored) {
        }
        if (rot != 0) {
            Matrix m = new Matrix();
            m.postRotate(rot);
            b = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
        }
        return b;
    }

    static String stamp() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
    }

    /** Saves a picture as a new file in Pictures/Edited. Returns its Uri. */
    static Uri savePicture(Context c, Bitmap bmp, boolean png) throws Exception {
        String name = "IMG_EDIT_" + stamp() + (png ? ".png" : ".jpg");
        String mime = png ? "image/png" : "image/jpeg";
        Bitmap.CompressFormat fmt = png ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG;
        ContentResolver cr = c.getContentResolver();
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Edited");
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri u = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (u == null) throw new java.io.IOException("insert");
            try (OutputStream out = cr.openOutputStream(u)) {
                bmp.compress(fmt, 95, out);
            }
            v.clear();
            v.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(u, v, null, null);
            return u;
        }
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Edited");
        dir.mkdirs();
        File f = new File(dir, name);
        try (FileOutputStream out = new FileOutputStream(f)) {
            bmp.compress(fmt, 95, out);
        }
        MediaScannerConnection.scanFile(c, new String[]{f.getAbsolutePath()}, new String[]{mime}, null);
        return Uri.fromFile(f);
    }

    /** Where an edited video is exported to first (the exporter needs a real file). */
    static File tempVideo(Context c) {
        File d = new File(c.getCacheDir(), "export");
        d.mkdirs();
        return new File(d, "VID_EDIT_" + stamp() + ".mp4");
    }

    /** Copies an exported video into Movies/Edited. */
    static Uri publishVideo(Context c, File src) throws Exception {
        String name = src.getName();
        ContentResolver cr = c.getContentResolver();
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Edited");
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri u = cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);
            if (u == null) throw new java.io.IOException("insert");
            try (InputStream in = new FileInputStream(src); OutputStream out = cr.openOutputStream(u)) {
                copy(in, out);
            }
            v.clear();
            v.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(u, v, null, null);
            src.delete();
            return u;
        }
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "Edited");
        dir.mkdirs();
        File f = new File(dir, name);
        try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(f)) {
            copy(in, out);
        }
        src.delete();
        MediaScannerConnection.scanFile(c, new String[]{f.getAbsolutePath()}, new String[]{"video/mp4"}, null);
        return Uri.fromFile(f);
    }

    private static void copy(InputStream in, OutputStream out) throws java.io.IOException {
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }
}
