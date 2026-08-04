package com.chajianzhushou.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.concurrent.ConcurrentHashMap;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 极简异步图片加载器（基于 OkHttp + LRU 内存缓存）
 * 项目未引入 Glide/Picasso，用最少代码实现"远程URL→ImageView"
 */
public class ImageLoader {
    private static final String TAG = "ImageLoader";
    private static final int MAX_CACHE_MB = 8;
    private static final ConcurrentHashMap<String, Bitmap> sCache = new ConcurrentHashMap<>();
    private static long sCacheBytes = 0L;

    private static ImageLoader sInstance;
    private final OkHttpClient client;
    private final Handler mainHandler;

    private ImageLoader(OkHttpClient client) {
        this.client = client;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public static synchronized ImageLoader with(OkHttpClient client) {
        if (sInstance == null) sInstance = new ImageLoader(client);
        return sInstance;
    }

    /** 清空内存图片缓存（一键清理用） */
    public static synchronized void clearCache() {
        sCache.clear();
        sCacheBytes = 0L;
    }

    public void load(String url, ImageView target, int placeholderResId) {
        if (target == null) return;
        target.setImageResource(placeholderResId);
        if (url == null || url.length() == 0) {
            Log.d(TAG, "skip: empty url");
            return;
        }
        Log.d(TAG, "load: " + url);
        final String tagUrl = url;
        target.setTag(R.id.image_loader_tag, url);
        final WeakReference<ImageView> ref = new WeakReference<>(target);

        // 1) 命中缓存直接显示
        Bitmap cached = sCache.get(url);
        if (cached != null && !cached.isRecycled()) {
            ImageView v = ref.get();
            if (v != null && url.equals(v.getTag(R.id.image_loader_tag))) {
                v.setImageBitmap(cached);
                Log.d(TAG, "cache hit: " + url);
            }
            return;
        }

        // 2) 异步下载
        Threads.io().execute(() -> {
            try {
                Request r = new Request.Builder().url(url).get().build();
                Response resp = client.newCall(r).execute();
                if (resp == null || !resp.isSuccessful()) {
                    Log.w(TAG, "http fail: url=" + url + " code=" + (resp == null ? "null" : resp.code()));
                    return;
                }
                ResponseBody body = resp.body();
                if (body == null) {
                    Log.w(TAG, "empty body: " + url);
                    return;
                }
                byte[] bytes;
                try (InputStream in = body.byteStream()) {
                    bytes = readAllBytes(in);
                }
                if (bytes == null || bytes.length == 0) {
                    Log.w(TAG, "zero bytes: " + url);
                    return;
                }

                Bitmap bitmap = decodeSampledBitmap(bytes, 1080, 1080);
                if (bitmap == null) {
                    Log.w(TAG, "decode bitmap fail, bytes=" + bytes.length);
                    return;
                }

                // 写缓存（简单 LRU：超了就清空一半）
                final int sz = bitmap.getByteCount();
                if (sCacheBytes + sz > MAX_CACHE_MB * 1024L * 1024L) {
                    sCache.clear();
                    sCacheBytes = 0;
                }
                sCache.put(tagUrl, bitmap);
                sCacheBytes += sz;

                mainHandler.post(() -> {
                    ImageView v = ref.get();
                    if (v == null) return;
                    Object t = v.getTag(R.id.image_loader_tag);
                    if (!tagUrl.equals(t)) return;
                    v.setImageBitmap(bitmap);
                    Log.d(TAG, "set bitmap: " + tagUrl + " w=" + bitmap.getWidth() + " h=" + bitmap.getHeight());
                });
            } catch (IOException e) {
                Log.w(TAG, "IO fail: url=" + url + " err=" + e.getMessage());
            } catch (Exception e) {
                Log.w(TAG, "load error: url=" + url + " err=" + e.getMessage());
            }
        });
    }

    /**
     * 带磁盘缓存的图片加载。
     * 优先读取本地缓存（以 billCode 为 key），缓存命中则直接解码显示；
     * 未命中则走网络下载并同步写入磁盘缓存。
     */
    public void load(String url, String billCode, ImageView target, int placeholderResId) {
        if (target == null) return;
        target.setImageResource(placeholderResId);
        if (url == null || url.length() == 0) return;
        final String tagUrl = url;
        target.setTag(R.id.image_loader_tag, url);
        final WeakReference<ImageView> ref = new WeakReference<>(target);

        // 0) 内存缓存命中
        Bitmap cached = sCache.get(url);
        if (cached != null && !cached.isRecycled()) {
            ImageView v = ref.get();
            if (v != null && url.equals(v.getTag(R.id.image_loader_tag))) {
                v.setImageBitmap(cached);
                return;
            }
        }

        // 1) 磁盘缓存命中 → 直接解码显示（校验URL：出库换新照片后旧图自动作废）
        if (billCode != null && billCode.length() > 0) {
            ImageCacheManager.init(null); // 确保缓存目录已创建
            File cacheFile = ImageCacheManager.getCachedFile(billCode, url);
            if (cacheFile != null) {
                Threads.io().execute(() -> {
                    try {
                        byte[] bytes = readAllBytes(new FileInputStream(cacheFile));
                        Bitmap bitmap = decodeSampledBitmap(bytes, 1080, 1080);
                        if (bitmap != null) {
                            putCachedBitmap(tagUrl, bitmap);
                            mainHandler.post(() -> {
                                ImageView v = ref.get();
                                if (v != null && tagUrl.equals(v.getTag(R.id.image_loader_tag))) {
                                    v.setImageBitmap(bitmap);
                                }
                            });
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "磁盘缓存读取失败: " + billCode + " " + e.getMessage());
                    }
                });
                return;
            }
        }

        // 2) 磁盘未命中 → 网络下载 + 写磁盘缓存
        Threads.io().execute(() -> {
            try {
                Request r = new Request.Builder().url(url).get().build();
                Response resp = client.newCall(r).execute();
                if (resp == null || !resp.isSuccessful()) return;
                ResponseBody body = resp.body();
                if (body == null) return;
                byte[] bytes;
                try (InputStream in = body.byteStream()) {
                    bytes = readAllBytes(in);
                }
                if (bytes == null || bytes.length == 0) return;

                // 写磁盘缓存（记录URL指纹，用于下次校验是否已换新照片）
                if (billCode != null && billCode.length() > 0) {
                    ImageCacheManager.cacheBytes(billCode, url, bytes);
                }

                Bitmap bitmap = decodeSampledBitmap(bytes, 1080, 1080);
                if (bitmap == null) return;
                putCachedBitmap(tagUrl, bitmap);
                mainHandler.post(() -> {
                    ImageView v = ref.get();
                    if (v != null && tagUrl.equals(v.getTag(R.id.image_loader_tag))) {
                        v.setImageBitmap(bitmap);
                    }
                });
            } catch (IOException e) {
                Log.w(TAG, "IO fail: " + url + " " + e.getMessage());
            } catch (Exception e) {
                Log.w(TAG, "load error: " + url + " " + e.getMessage());
            }
        });
    }

    private static byte[] readAllBytes(InputStream in) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(Math.max(8192, in.available()));
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            baos.write(buf, 0, n);
        }
        return baos.toByteArray();
    }

    private static Bitmap decodeSampledBitmap(byte[] bytes, int reqW, int reqH) {
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
            if (opts.outWidth <= 0 || opts.outHeight <= 0) {
                return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            }
            int sample = 1;
            int w = opts.outWidth;
            int h = opts.outHeight;
            while (w > reqW || h > reqH) {
                w /= 2;
                h /= 2;
                sample *= 2;
            }
            opts.inJustDecodeBounds = false;
            opts.inSampleSize = sample;
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
        } catch (OutOfMemoryError e) {
            Log.w(TAG, "OOM decode bytes=" + bytes.length);
            return null;
        } catch (Throwable t) {
            Log.w(TAG, "decode err: " + t.getMessage());
            return null;
        }
    }

    /** 查询缓存（用于预览大图，命中则直接展示无需再次下载） */
    public Bitmap getCachedBitmap(String url) {
        if (url == null) return null;
        Bitmap b = sCache.get(url);
        return (b != null && !b.isRecycled()) ? b : null;
    }

    /** 写入缓存（用于大图预览页下载完后也缓存一份，避免下次列表又要重新下载） */
    public void putCachedBitmap(String url, Bitmap bitmap) {
        if (url == null || bitmap == null || bitmap.isRecycled()) return;
        final int sz = bitmap.getByteCount();
        if (sCacheBytes + sz > MAX_CACHE_MB * 1024L * 1024L) {
            sCache.clear();
            sCacheBytes = 0;
        }
        Bitmap prev = sCache.put(url, bitmap);
        if (prev != null) sCacheBytes -= prev.getByteCount();
        sCacheBytes += sz;
    }

    /** 预览大图使用：无最大尺寸限制，允许 ARGB_8888；下载完成回调到主线程 */
    public void loadFull(final String url, final ImageView target, final int placeholderResId, final OnBitmapReadyListener listener) {
        loadFull(url, target, placeholderResId, listener, null);
    }

    /**
     * 预览大图使用（可携带磁盘缓存 key）：
     * 对比图等不在卡片缩略图加载链路中的图片，下载后按 key 写入磁盘缓存，
     * 避免签名 URL 过期后重新加载为空。
     */
    public void loadFull(final String url, final ImageView target, final int placeholderResId,
                         final OnBitmapReadyListener listener, final String diskCacheKey) {
        if (target == null) return;
        target.setImageResource(placeholderResId);
        if (url == null || url.length() == 0) {
            if (listener != null) listener.onBitmapReady(null);
            return;
        }
        final String tagUrl = url;
        target.setTag(R.id.image_loader_tag, url);
        final WeakReference<ImageView> ref = new WeakReference<>(target);

        Bitmap cached = sCache.get(url);
        if (cached != null && !cached.isRecycled()) {
            ImageView v = ref.get();
            if (v != null && url.equals(v.getTag(R.id.image_loader_tag))) {
                v.setImageBitmap(cached);
                if (listener != null) listener.onBitmapReady(cached);
            }
            return;
        }

        Threads.io().execute(() -> {
            try {
                Request r = new Request.Builder().url(url).get().build();
                Response resp = client.newCall(r).execute();
                if (resp == null || !resp.isSuccessful()) {
                    Log.w(TAG, "loadFull http fail: url=" + url + " code=" + (resp == null ? "null" : resp.code()));
                    mainHandler.post(() -> { if (listener != null) listener.onBitmapReady(null); });
                    return;
                }
                ResponseBody body = resp.body();
                if (body == null) {
                    mainHandler.post(() -> { if (listener != null) listener.onBitmapReady(null); });
                    return;
                }
                byte[] bytes;
                try (InputStream in = body.byteStream()) { bytes = readAllBytes(in); }
                if (bytes == null || bytes.length == 0) {
                    mainHandler.post(() -> { if (listener != null) listener.onBitmapReady(null); });
                    return;
                }
                // 下载成功：按需写磁盘缓存（如对比图），避免签名 URL 过期后无法再显示
                if (diskCacheKey != null && !diskCacheKey.isEmpty()) {
                    ImageCacheManager.cacheBytes(diskCacheKey, url, bytes);
                }
                // 预览：默认不做过大下采样，只对 2048 以上做保护
                Bitmap bitmap = decodeSampledBitmap(bytes, 2048, 2048);
                if (bitmap == null) {
                    mainHandler.post(() -> { if (listener != null) listener.onBitmapReady(null); });
                    return;
                }
                putCachedBitmap(tagUrl, bitmap);
                final Bitmap fb = bitmap;
                mainHandler.post(() -> {
                    ImageView v = ref.get();
                    if (v == null) return;
                    Object t = v.getTag(R.id.image_loader_tag);
                    if (!tagUrl.equals(t)) return;
                    v.setImageBitmap(fb);
                    if (listener != null) listener.onBitmapReady(fb);
                });
            } catch (IOException e) {
                Log.w(TAG, "loadFull IO fail: url=" + url + " err=" + e.getMessage());
                mainHandler.post(() -> { if (listener != null) listener.onBitmapReady(null); });
            } catch (Exception e) {
                Log.w(TAG, "loadFull error: url=" + url + " err=" + e.getMessage());
                mainHandler.post(() -> { if (listener != null) listener.onBitmapReady(null); });
            }
        });
    }

    /** 大图加载完成回调（用于关闭 loading 等） */
    public interface OnBitmapReadyListener {
        void onBitmapReady(Bitmap bitmap);
    }
}
