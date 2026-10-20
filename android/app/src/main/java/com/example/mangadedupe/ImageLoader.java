package com.example.mangadedupe;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 极简图片加载：LruCache + 按目标尺寸采样，避免 OOM。 */
public final class ImageLoader {

    public interface Callback {
        void onBitmap(Bitmap bmp);
    }

    private final ContentResolver cr;
    private final LruCache<String, Bitmap> cache;
    private final ExecutorService pool;
    private final Handler main = new Handler(Looper.getMainLooper());

    public ImageLoader(ContentResolver cr) {
        this.cr = cr;
        int maxKb = (int) (Runtime.getRuntime().maxMemory() / 1024 / 8);
        this.cache = new LruCache<String, Bitmap>(maxKb) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };
        this.pool = Executors.newFixedThreadPool(3);
    }

    public void load(final String uriStr, final int reqW, final Callback cb) {
        if (uriStr == null || uriStr.length() == 0) {
            cb.onBitmap(null);
            return;
        }
        Bitmap hit = cache.get(uriStr);
        if (hit != null) {
            cb.onBitmap(hit);
            return;
        }
        pool.execute(new Runnable() {
            @Override
            public void run() {
                final Bitmap bmp = decode(uriStr, reqW);
                if (bmp != null) cache.put(uriStr, bmp);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        cb.onBitmap(bmp);
                    }
                });
            }
        });
    }

    private Bitmap decode(String uriStr, int reqW) {
        InputStream in = null;
        try {
            Uri u = Uri.parse(uriStr);
            in = cr.openInputStream(u);
            if (in == null) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(in, null, o);
            in.close();
            in = null;
            if (o.outWidth <= 0) return null;

            int sample = 1;
            while (reqW > 0 && o.outWidth / (sample * 2) >= reqW) sample *= 2;

            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            in = cr.openInputStream(u);
            Bitmap bmp = BitmapFactory.decodeStream(in, null, o2);
            return bmp;
        } catch (Exception e) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    public void shutdown() {
        pool.shutdownNow();
    }
}
