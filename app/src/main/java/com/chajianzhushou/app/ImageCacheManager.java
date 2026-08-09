package com.chajianzhushou.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;

/**
 * 磁盘图片缓存管理器。
 * 以 billCode 为 key，将下载的图片字节存到 app 缓存目录，
 * 避免 S3 预签名 URL 过期后图片重新加载为空。
 */
public class ImageCacheManager {
    private static final String TAG = "ImgCache";
    private static final String CACHE_DIR = "pkg_images";
    private static final String KEY_CACHE_DAYS = "image_cache_days";
    private static final String KEY_CACHE_HOURS = "image_cache_expire_hours";
    private static final int DEFAULT_CACHE_DAYS = 7;
    private static final int MAX_CACHE_DAYS = 90;

    private static File sCacheDir;

    public static synchronized void init(Context context) {
        if (sCacheDir != null) return;
        Context appCtx = (context != null) ? context.getApplicationContext() : MainActivity.getAppContext();
        if (appCtx == null) return;
        sCacheDir = new File(appCtx.getCacheDir(), CACHE_DIR);
        if (!sCacheDir.exists()) {
            sCacheDir.mkdirs();
        }
        cleanExpired();
    }

    private static File ensureDir() {
        if (sCacheDir == null) {
            // 延迟初始化（可能从 QueryFragment 首次调用时 Context 才可用）
            Context ctx = MainActivity.getAppContext();
            if (ctx != null) init(ctx);
        }
        return sCacheDir;
    }

    /** 读取设置"图片缓存过期天数"（1~90，默认7天），并换算为毫秒 */
    private static long getMaxAgeMs() {
        try {
            Context ctx = MainActivity.getAppContext();
            if (ctx == null) return DEFAULT_CACHE_DAYS * 24L * 3600 * 1000L;
            SharedPreferences prefs = ctx.getSharedPreferences("chajianzhushou_prefs", Context.MODE_PRIVATE);
            // 新版按小时存储；旧版按天存储，读到旧值自动按 1 天=24 小时迁移
            int hours = prefs.getInt(KEY_CACHE_HOURS, -1);
            if (hours <= 0) {
                hours = prefs.getInt(KEY_CACHE_DAYS, DEFAULT_CACHE_DAYS) * 24;
            }
            if (hours < 1) hours = 1;
            if (hours > MAX_CACHE_DAYS * 24) hours = MAX_CACHE_DAYS * 24;
            return hours * 3600L * 1000L;
        } catch (Exception e) {
            return DEFAULT_CACHE_DAYS * 24L * 3600 * 1000L;
        }
    }

    /** 一键清理：删除磁盘缓存全部文件（含URL指纹），返回删除数量 */
    public static synchronized int clearAll() {
        File dir = ensureDir();
        if (dir == null) return 0;
        File[] files = dir.listFiles();
        if (files == null) return 0;
        int deleted = 0;
        for (File f : files) {
            if (f.isFile() && f.delete()) deleted++;
        }
        return deleted;
    }

    /** 根据 billCode 获取缓存文件 */
    /** 无 URL 校验的缓存读取（仅内部兜底：无 URL 可比对时按单号读缓存）；外部请使用带 URL 的版本 */
    private static File getCachedFile(String billCode) {
        if (billCode == null || billCode.isEmpty()) return null;
        File dir = ensureDir();
        if (dir == null) return null;
        File f = new File(dir, billCode + ".jpg");
        if (f.exists() && f.length() > 0) {
            // 检查是否过期
            if (System.currentTimeMillis() - f.lastModified() > getMaxAgeMs()) {
                f.delete();
                deleteUrlMeta(billCode);
                return null;
            }
            return f;
        }
        return null;
    }

    /**
     * 带 URL 校验的缓存读取。
     * 缓存写入时记录了对应的图片 URL；若当前 URL 与缓存时不一致
     * （如包裹出库后换了新照片），则删除旧图重新下载，避免一直显示旧图。
     */
    public static File getCachedFile(String billCode, String currentUrl) {
        if (currentUrl == null || currentUrl.isEmpty()) return getCachedFile(billCode);
        if (billCode == null || billCode.isEmpty()) return null;
        File dir = ensureDir();
        if (dir == null) return null;
        File f = new File(dir, billCode + ".jpg");
        if (f.exists() && f.length() > 0) {
            if (System.currentTimeMillis() - f.lastModified() > getMaxAgeMs()) {
                f.delete();
                deleteUrlMeta(billCode);
                return null;
            }
            String cachedUrl = readUrlMeta(billCode);
            if (cachedUrl != null && !cachedUrl.equals(currentUrl)) {
                // 图片 URL 已变化（出库换新照片等）→ 旧缓存作废
                Log.d(TAG, "URL已变化，删除旧图缓存: " + billCode);
                f.delete();
                deleteUrlMeta(billCode);
                return null;
            }
            return f;
        }
        return null;
    }

    /** 将网络流写入磁盘缓存 */
    public static void cacheStream(String billCode, String url, InputStream inputStream) {
        if (billCode == null || billCode.isEmpty() || inputStream == null) return;
        File dir = ensureDir();
        if (dir == null) return;
        File f = new File(dir, billCode + ".jpg");
        try (OutputStream out = new FileOutputStream(f)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = inputStream.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.flush();
            writeUrlMeta(billCode, url);
        } catch (Exception e) {
            Log.w(TAG, "缓存写入失败: " + billCode + " " + e.getMessage());
            f.delete();
            deleteUrlMeta(billCode);
        }
    }

    /** 照片身份（原始图片路径）→ 稳定短哈希，作为缓存文件名后缀 */
    private static String photoKeyHash(String photoKey) {
        if (photoKey == null || photoKey.isEmpty()) return "none";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(photoKey.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", d[i] & 0xFF));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(photoKey.hashCode());
        }
    }

    /**
     * 按“单号 + 原始图片路径”读取磁盘缓存。
     * 签名 URL 每次解析都会变，但照片没换时路径不变 → 命中同一份缓存，避免重复下载；
     * 照片真换了（新路径）→ 新文件名 → 自然重新下载。
     */
    public static File getCachedFileByPhotoKey(String billCode, String photoKey) {
        if (billCode == null || billCode.isEmpty()) return null;
        File dir = ensureDir();
        if (dir == null) return null;
        File f = new File(dir, billCode + "_" + photoKeyHash(photoKey) + ".jpg");
        if (f.exists() && f.length() > 0) {
            if (System.currentTimeMillis() - f.lastModified() > getMaxAgeMs()) {
                f.delete();
                return null;
            }
            return f;
        }
        return null;
    }

    /** 按“单号 + 原始图片路径”写入磁盘缓存 */
    public static void cacheBytesByPhotoKey(String billCode, String photoKey, byte[] bytes) {
        if (billCode == null || billCode.isEmpty() || bytes == null || bytes.length == 0) return;
        File dir = ensureDir();
        if (dir == null) return;
        File f = new File(dir, billCode + "_" + photoKeyHash(photoKey) + ".jpg");
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(bytes);
            out.flush();
        } catch (Exception e) {
            Log.w(TAG, "缓存写入失败: " + billCode + " " + e.getMessage());
            f.delete();
        }
    }
    /** 将已下载的字节数组写入磁盘缓存 */
    public static void cacheBytes(String billCode, String url, byte[] bytes) {
        if (billCode == null || billCode.isEmpty() || bytes == null || bytes.length == 0) return;
        File dir = ensureDir();
        if (dir == null) return;
        File f = new File(dir, billCode + ".jpg");
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(bytes);
            out.flush();
            writeUrlMeta(billCode, url);
        } catch (Exception e) {
            Log.w(TAG, "缓存写入失败: " + billCode + " " + e.getMessage());
            f.delete();
            deleteUrlMeta(billCode);
        }
    }

    // ====== URL 指纹（缓存对应的图片URL，用于出库换新照片后识别旧图） ======
    private static File urlMetaFile(String billCode) {
        return new File(ensureDir(), billCode + ".url");
    }

    private static void writeUrlMeta(String billCode, String url) {
        try {
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(urlMetaFile(billCode))) {
                out.write((url == null ? "" : url).getBytes("UTF-8"));
                out.flush();
            }
        } catch (Exception ignore) {}
    }

    private static String readUrlMeta(String billCode) {
        try {
            File m = urlMetaFile(billCode);
            if (!m.exists() || m.length() == 0) return null;
            byte[] b = new byte[(int) m.length()];
            try (FileInputStream in = new FileInputStream(m)) {
                int off = 0;
                while (off < b.length) {
                    int n = in.read(b, off, b.length - off);
                    if (n < 0) break;
                    off += n;
                }
            }
            return new String(b, "UTF-8").trim();
        } catch (Exception e) {
            return null;
        }
    }

    private static void deleteUrlMeta(String billCode) {
        try { urlMetaFile(billCode).delete(); } catch (Exception ignore) {}
    }

    /** 清理超过 7 天的缓存文件 */
    private static void cleanExpired() {
        File dir = ensureDir();
        if (dir == null) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        long cutoff = System.currentTimeMillis() - getMaxAgeMs();
        int deleted = 0;
        for (File f : files) {
            if (f.isFile() && f.lastModified() < cutoff) {
                if (f.delete()) deleted++;
            }
        }
        if (deleted > 0) Log.d(TAG, "清理过期缓存: " + deleted + " 个文件");
    }
}
