package org.thermal.wp09;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.thermal.wp09.InstrumentUi.*;

public final class GalleryActivity extends AppCompatActivity {
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final ExecutorService thumbnails = Executors.newFixedThreadPool(2);
    private final LruCache<Uri, Bitmap> cache = new LruCache<Uri, Bitmap>(5 * 1024 * 1024) {
        @Override protected int sizeOf(Uri key, Bitmap image) { return image.getByteCount(); }
    };
    private final List<CaptureLibrary> entries = new ArrayList<>();
    private final Tiles tiles = new Tiles();
    private TextView empty;
    private final ActivityResultLauncher<String[]> picker = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) return;
                String type = getContentResolver().getType(uri);
                boolean movie = type != null && type.startsWith("video/");
                open(uri, movie);
            });
    private final BroadcastReceiver saved = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { refresh(); }
    };

    @Override protected void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        setContentView(root);
        landscape(this, root);
        LinearLayout header = row(this);
        header.setPadding(dp(this, 8), dp(this, 2), dp(this, 8), dp(this, 2));
        root.addView(header);
        LinearLayout back = row(this);
        header.addView(back, new LinearLayout.LayoutParams(dp(this, 80), -2));
        action(back, "返回", R.drawable.ic_back, this::finish);
        TextView title = text(this, "相册", 20, TEXT);
        title.setGravity(Gravity.CENTER);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout imp = row(this);
        header.addView(imp, new LinearLayout.LayoutParams(dp(this, 104), -2));
        action(imp, "打开文件", R.drawable.ic_folder,
                () -> picker.launch(new String[]{"image/png", "image/jpeg", "video/mp4"}));

        FrameLayout content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        GridView grid = new GridView(this);
        grid.setPadding(dp(this, 12), 0, dp(this, 12), dp(this, 8));
        grid.setHorizontalSpacing(dp(this, 8));
        grid.setVerticalSpacing(dp(this, 8));
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setColumnWidth(dp(this, 138));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setAdapter(tiles);
        grid.setOnItemClickListener((parent, v, pos, id) -> {
            CaptureLibrary item = entries.get(pos);
            open(item.uri, item.movie);
        });
        content.addView(grid);
        empty = text(this, "正在读取…", 15, MUTED);
        empty.setGravity(Gravity.CENTER);
        content.addView(empty);
        grid.setEmptyView(empty);
    }

    private void open(Uri uri, boolean movie) {
        startActivity(new Intent(this, ReviewActivity.class).putExtra("uri", uri.toString()).putExtra("movie", movie));
    }

    @Override protected void onStart() {
        super.onStart();
        ContextCompat.registerReceiver(this, saved, new IntentFilter(ThermalMedia.SAVED), ContextCompat.RECEIVER_NOT_EXPORTED);
        refresh();
    }

    private void refresh() {
        loader.execute(() -> {
            try {
                List<CaptureLibrary> list = CaptureLibrary.list(this);
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    entries.clear();
                    entries.addAll(list);
                    empty.setText("暂无拍摄文件");
                    tiles.notifyDataSetChanged();
                });
            } catch (RuntimeException e) {
                runOnUiThread(() -> empty.setText("相册读取失败，可点“打开文件”"));
            }
        });
    }

    @Override protected void onStop() { unregisterReceiver(saved); super.onStop(); }
    @Override protected void onDestroy() {
        loader.shutdownNow();
        thumbnails.shutdownNow();
        cache.evictAll();
        super.onDestroy();
    }

    private final class Tile extends FrameLayout {
        final ImageView image;
        final TextView caption;
        Future<?> job;
        Uri uri;
        Tile() {
            super(GalleryActivity.this);
            setBackgroundColor(TILE);
            setLayoutParams(new GridView.LayoutParams(-1, dp(getContext(), 108)));
            image = new ImageView(getContext());
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            addView(image);
            caption = text(getContext(), "", 12, TEXT);
            caption.setBackgroundColor(0xcc151515);
            caption.setPadding(dp(getContext(), 6), 0, 0, 0);
            addView(caption, new FrameLayout.LayoutParams(-1, dp(getContext(), 24), Gravity.BOTTOM));
        }
    }

    private final class Tiles extends BaseAdapter {
        private final SimpleDateFormat format = new SimpleDateFormat("MM-dd  HH:mm:ss", Locale.ROOT);
        @Override public int getCount() { return entries.size(); }
        @Override public CaptureLibrary getItem(int i) { return entries.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public View getView(int i, View reused, ViewGroup parent) {
            CaptureLibrary item = entries.get(i);
            Tile cell = reused == null ? new Tile() : (Tile) reused;
            if (cell.job != null) cell.job.cancel(false);
            cell.uri = item.uri;
            cell.setContentDescription((item.movie ? "录像 " : "照片 ") + item.name);
            cell.caption.setText((item.movie ? "▶   " : "") + format.format(new Date(item.date)));
            Bitmap existing = cache.get(item.uri);
            cell.image.setImageBitmap(existing);
            if (existing == null) cell.job = thumbnails.submit(() -> {
                try {
                    Bitmap bitmap = item.thumbnail(GalleryActivity.this);
                    if (bitmap != null) runOnUiThread(() -> {
                        if (isDestroyed()) return;
                        cache.put(item.uri, bitmap);
                        if (item.uri.equals(cell.uri)) cell.image.setImageBitmap(bitmap);
                    });
                } catch (IOException | RuntimeException ignored) { /* File can be moved in another app. */ }
            });
            return cell;
        }
    }
}
