package com.chajianzhushou.app;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.ImageView;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 图片 URL 解析与懒加载（从 QueryFragment 拆出）：
 * - 会话级 URL 解析缓存（单号+原始路径 → 签名 URL）；
 * - 卡片图片加载：完整 URL 直接用，只有原始路径则查缓存或异步解析后加载；
 * - 手动查询清空缓存强制重新解析。
 */
public class ImageUrlResolver {

    private static final String TAG = "ImageUrlResolver";

    /** 解析成功回调（供预览大图同步等场景） */
    public interface OnUrlLoaded {
        void onUrl(String billCode, String url);
    }

    private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();
    private DirectApiClient directApiClient;
    private ApiService apiService;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    /** 解析成功监听（QueryFragment 注册，用于把 URL 补充进预览翻页列表） */
    private OnUrlLoaded urlLoadedListener;

    public void attach(DirectApiClient client, ApiService service) {
        this.directApiClient = client;
        this.apiService = service;
    }

    public void setUrlLoadedListener(OnUrlLoaded listener) {
        this.urlLoadedListener = listener;
    }

    /** 缓存键：单号 + 原始图片路径（路径变化=换新照片，旧 URL 不可复用） */
    private static String key(String billCode, String rawImgPath) {
        return (billCode == null ? "" : billCode) + "\u0001" + (rawImgPath == null ? "" : rawImgPath);
    }

    public String getCachedUrl(String billCode, String rawImgPath) {
        return cache.get(key(billCode, rawImgPath));
    }

    public void putCachedUrl(String billCode, String rawImgPath, String url) {
        if (billCode != null && billCode.length() > 0) {
            cache.put(key(billCode, rawImgPath), url);
        }
    }

    public void clearCache() {
        cache.clear();
    }

    /**
     * 加载卡片图片：有完整 URL（服务器模式）直接加载；只有原始路径（直连模式）查缓存或异步解析。
     * @return 已解析/直接使用的 URL（未解析时返回空串）
     */
    public String loadCardImage(ImageView iv, String trackingNumber, String imageUrl, String rawImgPath) {
        if (iv == null) return "";
        if (imageUrl.length() > 0 && apiService != null) {
            String resolved = apiService.resolveImageUrl(imageUrl);
            ImageLoader.with(apiService.getOkHttpClient()).load(resolved, trackingNumber, iv, R.drawable.bg_image_placeholder);
            return resolved;
        }
        iv.setImageResource(R.drawable.bg_image_placeholder);
        if (rawImgPath.length() > 0 && trackingNumber.length() > 0 && directApiClient != null) {
            String cachedUrl = getCachedUrl(trackingNumber, rawImgPath);
            if (cachedUrl != null && cachedUrl.length() > 0) {
                // 本会话已解析过相同路径：直接加载，不再重复请求 URL
                ImageLoader.with(apiService != null ? apiService.getOkHttpClient() : null)
                        .load(cachedUrl, trackingNumber, iv, R.drawable.bg_image_placeholder);
            } else {
                resolveAndLoad(iv, trackingNumber, rawImgPath, urlLoadedListener);
            }
        }
        return "";
    }

    /**
     * 直连模式：按原始图片路径异步解析 URL 并加载。
     * 用 "raw:单号:路径" 作为 ImageView 的 URL 标记，防止卡片复用/重建后迟到的回调覆盖错误图片；
     * 解析成功同时记入缓存，供预览列表重建时补充。
     */
    public void resolveAndLoad(final ImageView iv, final String billCode, final String rawImgPath, final OnUrlLoaded cb) {
        if (iv == null || billCode == null || billCode.length() == 0
                || rawImgPath == null || rawImgPath.length() == 0) return;
        final String marker = "raw:" + billCode + ":" + rawImgPath;
        iv.setTag(R.id.image_loader_tag, marker);
        try {
            if (directApiClient == null) return;
            directApiClient.resolveImageUrl(billCode, rawImgPath, new DirectApiClient.ImageUrlCallback() {
                @Override public void onUrl(final String url) {
                    if (url == null || url.length() == 0) return;
                    mainHandler.post(() -> {
                        if (iv == null) return;
                        Object t = iv.getTag(R.id.image_loader_tag);
                        if (!marker.equals(t)) return;
                        ImageLoader.with(apiService != null ? apiService.getOkHttpClient() : null)
                                .load(url, billCode, iv, R.drawable.bg_image_placeholder);
                        putCachedUrl(billCode, rawImgPath, url);
                        if (cb != null) cb.onUrl(billCode, url);
                    });
                }
                @Override public void onError(String error) {
                    Log.w(TAG, "解析图片URL失败: " + billCode + " " + error);
                }
            });
        } catch (Exception e) {
            Log.w(TAG, "resolveAndLoad 异常: " + e.getMessage());
        }
    }
}
