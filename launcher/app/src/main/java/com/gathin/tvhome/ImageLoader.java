package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Loads remote images into ImageViews, downsampled to the view size and kept in a small memory cache. */
final class ImageLoader {

    private static final ExecutorService POOL = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** About 8 MB: a couple of rows of backdrops, small enough for 1-2 GB TVs. */
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    private ImageLoader() {
    }

    /** Shows url in view (blank until loaded). Views recycled for another url meanwhile are left alone. */
    static void load(ImageView view, String url, int targetWidth) {
        view.setTag(R.id.image_url, url);
        Bitmap hit = url == null ? null : CACHE.get(url);
        view.setImageBitmap(hit);
        if (hit != null || url == null) return;
        POOL.execute(() -> {
            Bitmap b = fetch(url, targetWidth);
            if (b != null) CACHE.put(url, b);
            MAIN.post(() -> {
                if (url.equals(view.getTag(R.id.image_url))) view.setImageBitmap(b);
            });
        });
    }

    private static Bitmap fetch(String url, int targetWidth) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10_000);
            c.setReadTimeout(15_000);
            c.setInstanceFollowRedirects(true);
            if (c.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
            byte[] data;
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] chunk = new byte[16 * 1024];
                int n;
                while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
                data = buf.toByteArray();
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= targetWidth) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
