package org.thermal.wp09;

import android.content.ContentUris;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Size;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

final class CaptureLibrary {
    final Uri uri;
    final String name;
    final boolean movie;
    final long date;

    CaptureLibrary(Uri uri, String name, boolean movie, long date) {
        this.uri = uri;
        this.name = name;
        this.movie = movie;
        this.date = date;
    }

    static List<CaptureLibrary> list(Context c) {
        List<CaptureLibrary> result = new ArrayList<>();
        for (boolean movie : new boolean[]{false, true}) {
            String directory = movie ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES;
            // Include the app-specific directory after an Android OS upgrade too.
            File dir = c.getExternalFilesDir(directory);
            File[] files = dir == null ? null : dir.listFiles();
            if (files != null) for (File f : files) {
                String name = f.getName().toLowerCase(Locale.ROOT);
                if (f.isFile() && (movie ? name.endsWith(".mp4") : name.endsWith(".png"))) {
                    result.add(new CaptureLibrary(Uri.fromFile(f), f.getName(), movie, f.lastModified()));
                }
            }
            if (Build.VERSION.SDK_INT < 29) continue;
            Uri collection = movie ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            String[] cols = {MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.DATE_ADDED};
            try (Cursor rows = c.getContentResolver().query(collection, cols,
                    MediaStore.MediaColumns.RELATIVE_PATH + "=? AND " + MediaStore.MediaColumns.IS_PENDING + "=0",
                    new String[]{directory + "/WP09/"}, null)) {
                if (rows != null) while (rows.moveToNext()) {
                    result.add(new CaptureLibrary(ContentUris.withAppendedId(collection, rows.getLong(0)),
                            rows.getString(1), movie, rows.getLong(2) * 1000));
                }
            }
        }
        result.sort(Comparator.comparingLong((CaptureLibrary item) -> item.date).reversed()
                .thenComparing(item -> item.name, Comparator.reverseOrder()));
        return result;
    }

    Bitmap thumbnail(Context c) throws IOException {
        try {
            if (movie) {
                AssetFileDescriptor fd = c.getContentResolver().openAssetFileDescriptor(uri, "r");
                if (fd != null) try (MovieArchive.Reader reader = new MovieArchive.Reader(fd)) {
                    if (reader.size() > 0) return reader.frame(0).bitmap();
                }
            } else try (InputStream in = c.getContentResolver().openInputStream(uri)) {
                ThermalCapture photo = in == null ? null : RadiometricPng.read(in);
                if (photo != null) return photo.bitmap();
            }
        } catch (IOException | RuntimeException ignored) { /* Show the standard media thumbnail instead. */ }
        if (Build.VERSION.SDK_INT >= 29 && "content".equals(uri.getScheme())) {
            return c.getContentResolver().loadThumbnail(uri, new Size(240, 180), null);
        }
        if (!movie) return image(c, uri, 320);
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(c, uri);
            if (Build.VERSION.SDK_INT >= 27) return r.getScaledFrameAtTime(0,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 240, 180);
            return r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
        } finally {
            r.release();
        }
    }

    static Bitmap image(Context c, Uri uri, int limit) throws IOException {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(in, null, options); }
        options.inSampleSize = 1;
        while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > limit) options.inSampleSize *= 2;
        options.inJustDecodeBounds = false;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            Bitmap image = BitmapFactory.decodeStream(in, null, options);
            if (image == null) throw new IOException("Image unreadable");
            return image;
        }
    }
}
