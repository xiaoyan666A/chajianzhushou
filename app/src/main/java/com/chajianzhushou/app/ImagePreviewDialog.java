package com.chajianzhushou.app;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.util.Log;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import okhttp3.OkHttpClient;

/**
 * 点击包裹/超时件缩略图后弹出的全屏大图预览对话框
 * - 背景：纯黑半透明（#E0000000，~87% 不透明）
 * - 内容：居中 ImageView（FIT_CENTER），初始 ProgressBar
 * - 交互：
 *   - 左右两侧垂直居中按钮：‹ 上一张 / › 下一张（支持循环翻页）
 *   - 顶部：居中显示 "当前/总数    单号：xxx"；右上角：关闭按钮
 *   - 点击空白 / 返回键 → 关闭
 * - 图片加载：优先命中 ImageLoader 缓存；否则 loadFull 下载完整版本（下采样上限 2048px）
 */
public class ImagePreviewDialog extends Dialog {

    private final List<String> imageUrls;
    private final List<String> trackingNumbers;
    private final OkHttpClient httpClient;
    private int currentIndex;

    private ImageView iv;
    private ProgressBar pb;
    private TextView tvTitle;
    private View btnPrev;
    private View btnNext;
    private final boolean showNavButtons;

    // 多指缩放状态：双指捏合 0.5x~3x，以视图中心为锚点（保证居中），带比例提示
    private float scaleFactor = 1f;
    private boolean zooming = false;
    private ScaleGestureDetector scaleDetector;
    private GestureDetector doubleTapDetector;
    private TextView zoomIndicator;

    // 当前正在展示的预览对话框（供自动刷新发现换新照片时同步刷新大图）
    private static volatile ImagePreviewDialog sActiveDialog;

    public static ImagePreviewDialog getActiveDialog() { return sActiveDialog; }

    @Override
    public void show() {
        super.show();
        sActiveDialog = this;
    }

    @Override
    public void dismiss() {
        super.dismiss();
        if (sActiveDialog == this) sActiveDialog = null;
    }

    /**
     * 自动刷新后包裹换了新照片时调用：
     * 仅当预览正打开且当前展示的正是该单号的图片时，替换为新 URL 并重新加载（重置缩放）。
     */
    public void refreshImage(String newUrl, String trackingNo) {
        if (newUrl == null || newUrl.length() == 0 || !isShowing()) return;
        if (trackingNo != null && trackingNo.length() > 0
                && trackingNumbers != null && currentIndex < trackingNumbers.size()) {
            String curNo = trackingNumbers.get(currentIndex);
            if (curNo == null || !curNo.equals(trackingNo)) return;
        }
        if (currentIndex >= 0 && currentIndex < imageUrls.size()) {
            if (newUrl.equals(imageUrls.get(currentIndex))) return;
            imageUrls.set(currentIndex, newUrl);
            loadCurrentImage();
        }
    }

    public ImagePreviewDialog(@NonNull Context context,
                              List<String> imageUrls,
                              int startIndex,
                              List<String> trackingNumbers,
                              OkHttpClient httpClient) {
        this(context, imageUrls, startIndex, trackingNumbers, httpClient, true);
    }

    public ImagePreviewDialog(@NonNull Context context,
                              List<String> imageUrls,
                              int startIndex,
                              List<String> trackingNumbers,
                              OkHttpClient httpClient,
                              boolean showNavButtons) {
        super(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        this.imageUrls = (imageUrls == null) ? new ArrayList<>() : imageUrls;
        this.trackingNumbers = (trackingNumbers == null) ? new ArrayList<>() : trackingNumbers;
        this.currentIndex = (startIndex >= 0 && startIndex < this.imageUrls.size()) ? startIndex : 0;
        this.httpClient = httpClient;
        this.showNavButtons = showNavButtons;
    }

    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        // 根容器：全屏 FrameLayout，黑色半透明背景
        FrameLayout root = new FrameLayout(getContext());
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.setBackgroundColor(0xE0000000);
        root.setOnClickListener(v -> dismiss());

        // 居中 ImageView
        iv = new ImageView(getContext());
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER);
        iv.setLayoutParams(ilp);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setOnClickListener(v -> {
            // 点击图片不关闭，避免误操作
        });

        // 居中 ProgressBar
        pb = new ProgressBar(getContext());
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                dp(56), dp(56), Gravity.CENTER);
        pb.setLayoutParams(plp);
        pb.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(0xFF00F5D4));

        // ===== 顶部栏：关闭按钮 + 标题 =====
        // 右上角关闭按钮
        android.widget.Button close = new android.widget.Button(getContext());
        close.setText("✕  关闭预览");
        close.setTextColor(0xFFFFFFFF);
        close.setTextSize(18f);
        close.setTypeface(Typeface.DEFAULT_BOLD);
        close.setAllCaps(false);
        GradientDrawable closeBg = new GradientDrawable();
        closeBg.setShape(GradientDrawable.RECTANGLE);
        closeBg.setCornerRadius(dp(14));
        closeBg.setStroke(dp(2), 0xFFFFFFFF);
        closeBg.setColor(0x55000000);
        close.setBackground(closeBg);
        int padHV = dp(16), padVV = dp(12);
        close.setPadding(padHV, padVV, padHV, padVV);
        close.setMinWidth(dp(160));
        close.setMinHeight(dp(56));
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        clp.setMargins(dp(16), dp(32), dp(16), dp(16));
        close.setLayoutParams(clp);
        close.setOnClickListener(v -> dismiss());
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            close.setElevation(dp(12));
        }

        // 顶部标题："当前/总数    单号：xxx"
        tvTitle = new TextView(getContext());
        tvTitle.setTextColor(0xFFFFFFFF);
        tvTitle.setTextSize(15f);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tvTitle.setGravity(Gravity.CENTER);
        tvTitle.setMaxLines(1);
        GradientDrawable titleBg = new GradientDrawable();
        titleBg.setShape(GradientDrawable.RECTANGLE);
        titleBg.setCornerRadius(dp(12));
        titleBg.setColor(0x66000000);
        titleBg.setStroke(dp(1), 0x66FFFFFF);
        tvTitle.setBackground(titleBg);
        int tPadH = dp(18), tPadV = dp(10);
        tvTitle.setPadding(tPadH, tPadV, tPadH, tPadV);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        tlp.setMargins(dp(16), dp(36), dp(16), dp(16));
        tvTitle.setLayoutParams(tlp);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            tvTitle.setElevation(dp(10));
        }

        // ===== 左右翻页按钮（使用 TextView + Unicode 箭头，比系统图标更清晰美观） =====
        int btnSize = dp(52);
        GradientDrawable navBgTemplate = new GradientDrawable();
        navBgTemplate.setShape(GradientDrawable.OVAL);
        navBgTemplate.setColor(0x55000000);
        int strokePx = (int)(getContext().getResources().getDisplayMetrics().density * 1.5f + 0.5f);
        navBgTemplate.setStroke(strokePx, 0xAAFFFFFF);

        btnPrev = new TextView(getContext());
        ((TextView) btnPrev).setText("◀");
        ((TextView) btnPrev).setTextColor(0xFFFFFFFF);
        ((TextView) btnPrev).setTextSize(22f);
        ((TextView) btnPrev).setGravity(Gravity.CENTER);
        ((TextView) btnPrev).setTypeface(Typeface.DEFAULT_BOLD);
        btnPrev.setBackground(navBgTemplate.getConstantState().newDrawable());
        btnPrev.setContentDescription("上一张");
        FrameLayout.LayoutParams prevLp = new FrameLayout.LayoutParams(btnSize, btnSize,
                Gravity.START | Gravity.CENTER_VERTICAL);
        prevLp.setMargins(dp(12), 0, 0, 0);
        btnPrev.setLayoutParams(prevLp);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            btnPrev.setElevation(dp(12));
        }
        btnPrev.setOnClickListener(v -> {
            if (imageUrls.size() <= 1) return;
            currentIndex = (currentIndex - 1 + imageUrls.size()) % imageUrls.size();
            loadCurrentImage();
        });

        btnNext = new TextView(getContext());
        ((TextView) btnNext).setText("▶");
        ((TextView) btnNext).setTextColor(0xFFFFFFFF);
        ((TextView) btnNext).setTextSize(22f);
        ((TextView) btnNext).setGravity(Gravity.CENTER);
        ((TextView) btnNext).setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable nextBg = new GradientDrawable();
        nextBg.setShape(GradientDrawable.OVAL);
        nextBg.setColor(0x55000000);
        nextBg.setStroke(strokePx, 0xAAFFFFFF);
        btnNext.setBackground(nextBg);
        btnNext.setContentDescription("下一张");
        FrameLayout.LayoutParams nextLp = new FrameLayout.LayoutParams(btnSize, btnSize,
                Gravity.END | Gravity.CENTER_VERTICAL);
        nextLp.setMargins(0, 0, dp(12), 0);
        btnNext.setLayoutParams(nextLp);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            btnNext.setElevation(dp(12));
        }
        btnNext.setOnClickListener(v -> {
            if (imageUrls.size() <= 1) return;
            currentIndex = (currentIndex + 1) % imageUrls.size();
            loadCurrentImage();
        });

        // 根据模式决定是否显示翻页按钮
        if (!showNavButtons) {
            btnPrev.setVisibility(View.GONE);
            btnNext.setVisibility(View.GONE);
        }

        // 添加顺序：底层 iv → pb → title → nav buttons → close（最上层）
        root.addView(iv);
        root.addView(pb);
        root.addView(tvTitle);
        root.addView(btnPrev);
        root.addView(btnNext);
        root.addView(close);

        // ===== 多指缩放：双指捏合 0.5x~3x，中心缩放 + 比例提示 =====
        zoomIndicator = new TextView(getContext());
        zoomIndicator.setTextColor(0xFFFFFFFF);
        zoomIndicator.setTextSize(13f);
        zoomIndicator.setTypeface(Typeface.DEFAULT_BOLD);
        zoomIndicator.setGravity(Gravity.CENTER);
        GradientDrawable zbBg = new GradientDrawable();
        zbBg.setShape(GradientDrawable.RECTANGLE);
        zbBg.setCornerRadius(dp(14));
        zbBg.setColor(0x99000000);
        zbBg.setStroke(dp(1), 0x66FFFFFF);
        zoomIndicator.setBackground(zbBg);
        int zbPadH = dp(16), zbPadV = dp(8);
        zoomIndicator.setPadding(zbPadH, zbPadV, zbPadH, zbPadV);
        zoomIndicator.setAlpha(0f);
        FrameLayout.LayoutParams zlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        zlp.setMargins(0, 0, 0, dp(30));
        zoomIndicator.setLayoutParams(zlp);
        root.addView(zoomIndicator);

        scaleDetector = new ScaleGestureDetector(getContext(), new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector detector) {
                // 防御：手势开始时再次确保缩放锚点在视图中心（Dialog 窗口可能刚完成布局）
                if (iv.getWidth() > 0 && iv.getHeight() > 0) {
                    iv.setPivotX(iv.getWidth() / 2f);
                    iv.setPivotY(iv.getHeight() / 2f);
                }
                zooming = true;
                showZoomIndicator(true);
                return true;
            }
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                // 边界限制：缩放比例钳制在 0.5x~3x
                float next = scaleFactor * detector.getScaleFactor();
                next = Math.max(0.5f, Math.min(3f, next));
                if (next != scaleFactor) {
                    scaleFactor = next;
                    applyScale(next, false);
                    if (zoomIndicator != null) zoomIndicator.setText(Math.round(scaleFactor * 100) + "%");
                }
                return true;
            }
            @Override
            public void onScaleEnd(ScaleGestureDetector detector) {
                zooming = false;
                animateToBounds();
                showZoomIndicator(false);
            }
        });
        doubleTapDetector = new GestureDetector(getContext(), new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                resetZoom();
                return true;
            }
        });
        iv.setOnTouchListener((v, event) -> {
            if (scaleDetector != null) scaleDetector.onTouchEvent(event);
            if (doubleTapDetector != null) doubleTapDetector.onTouchEvent(event);
            // 捏合进行中或双指按下时消费事件，避免误触发点击
            return zooming || event.getPointerCount() >= 2;
        });
        // 以视图中心为缩放锚点：缩放时图片始终围绕中心，保持居中
        // 必须在每次布局完成后同步 pivot（Dialog 窗口显示后才完成布局；
        // 过早的 iv.post 可能拿到 0 宽高，导致 pivot 落在左上角、缩放时图片抖动偏移）
        iv.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int w = r - l;
            int h = b - t;
            if (w > 0 && h > 0) {
                v.setPivotX(w / 2f);
                v.setPivotY(h / 2f);
            }
        });
        setContentView(root);

        Window win = getWindow();
        if (win != null) {
            win.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            win.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        setCanceledOnTouchOutside(true);

        if (imageUrls.isEmpty()) {
            pb.setVisibility(View.GONE);
            if (tvTitle != null) tvTitle.setText("0/0    暂无图片");
            Toast.makeText(getContext(), "图片地址为空", Toast.LENGTH_SHORT).show();
            return;
        }
        updateNavButtonsState();
        loadCurrentImage();
    }

    private void updateNavButtonsState() {
        if (btnPrev == null || btnNext == null) return;
        boolean onlyOne = imageUrls.size() <= 1;
        btnPrev.setEnabled(!onlyOne);
        btnNext.setEnabled(!onlyOne);
        btnPrev.setAlpha(onlyOne ? 0.3f : 1.0f);
        btnNext.setAlpha(onlyOne ? 0.3f : 1.0f);
    }

    private void loadCurrentImage() {
        if (imageUrls == null || imageUrls.isEmpty()
                || currentIndex < 0 || currentIndex >= imageUrls.size()) {
            return;
        }
        // 更新顶部标题
        String trackingNo = "";
        if (trackingNumbers != null && currentIndex < trackingNumbers.size()) {
            trackingNo = trackingNumbers.get(currentIndex);
        }
        String titleStr = (currentIndex + 1) + "/" + imageUrls.size();
        if (trackingNo != null && trackingNo.length() > 0) {
            titleStr += "    单号：" + trackingNo;
        }
        if (tvTitle != null) tvTitle.setText(titleStr);

        // 显示加载
        if (pb != null) pb.setVisibility(View.VISIBLE);
        if (iv != null) iv.setImageBitmap(null);
        // 切换图片时重置缩放（立即、无动画），避免上一张的缩放延续到下一张
        scaleFactor = 1f;
        if (iv != null) {
            iv.setScaleX(1f);
            iv.setScaleY(1f);
        }
        if (zoomIndicator != null) {
            zoomIndicator.removeCallbacks(zoomFadeRunnable);
            zoomIndicator.setAlpha(0f);
        }

        String url = imageUrls.get(currentIndex);
        if (url == null || url.length() == 0) {
            if (pb != null) pb.setVisibility(View.GONE);
            Toast.makeText(getContext(), "当前图片地址为空", Toast.LENGTH_SHORT).show();
            return;
        }
        if (httpClient == null) {
            if (pb != null) pb.setVisibility(View.GONE);
            Toast.makeText(getContext(), "HTTP客户端未就绪", Toast.LENGTH_SHORT).show();
            return;
        }

        // 1) 优先查磁盘缓存（以单号为 key，避免 S3 预签名 URL 过期后预览空白；URL变化时旧图自动作废）
        java.io.File diskFile = (trackingNo != null && trackingNo.length() > 0)
                ? ImageCacheManager.getCachedFile(trackingNo, url) : null;
        if (diskFile != null) {
            try {
                Bitmap bmp = BitmapFactory.decodeFile(diskFile.getAbsolutePath());
                if (bmp != null && !bmp.isRecycled()) {
                    if (pb != null) pb.setVisibility(View.GONE);
                    if (iv != null) iv.setImageBitmap(bmp);
                    return;
                }
                // 解码失败 → 磁盘缓存文件可能损坏，删除它以允许后续重新下载
                Log.w("ImgPreview", "磁盘缓存解码失败，删除损坏文件: " + trackingNo);
                diskFile.delete();
            } catch (Exception e) {
                Log.w("ImgPreview", "磁盘缓存解码异常: " + trackingNo + " " + e.getMessage());
                try { diskFile.delete(); } catch (Exception ignored) {}
            }
        }

        // 2) 内存缓存
        ImageLoader loader = ImageLoader.with(httpClient);
        Bitmap cached = loader.getCachedBitmap(url);
        if (cached != null) {
            if (pb != null) pb.setVisibility(View.GONE);
            if (iv != null) iv.setImageBitmap(cached);
            return;
        }
        // 3) 网络下载兜底
        final String fallbackTrackingNo = trackingNo;
        loader.loadFull(url, iv, 0, bitmap -> {
            if (pb != null) pb.setVisibility(View.GONE);
            if (bitmap == null) {
                // 网络下载失败（URL 可能已过期），延迟 2 秒再查一次磁盘缓存
                // 场景：缩略图首次下载尚未完成时用户就点击了放大
                if (fallbackTrackingNo != null && fallbackTrackingNo.length() > 0) {
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                        java.io.File retryFile = ImageCacheManager.getCachedFile(fallbackTrackingNo, url);
                        if (retryFile != null) {
                            try {
                                Bitmap retryBmp = BitmapFactory.decodeFile(retryFile.getAbsolutePath());
                                if (retryBmp != null && !retryBmp.isRecycled() && iv != null) {
                                    iv.setImageBitmap(retryBmp);
                                    return;
                                }
                            } catch (Exception ignored) {}
                        }
                        // 彻底失败：提示用户
                        try {
                            Toast.makeText(getContext(), "图片加载失败，请稍后重试", Toast.LENGTH_SHORT).show();
                        } catch (Exception ignored) {}
                    }, 2000);
                } else {
                    try {
                        Toast.makeText(getContext(), "图片加载失败", Toast.LENGTH_SHORT).show();
                    } catch (Exception ignored) {}
                }
            }
        });
    }

    // ===== 多指缩放辅助 =====

    /** 应用缩放：animated=true 时用属性动画平滑过渡（手势结束回弹/复位场景） */
    private void applyScale(float f, boolean animated) {
        if (iv == null) return;
        if (animated) {
            iv.animate().scaleX(f).scaleY(f).setDuration(180).start();
        } else {
            iv.setScaleX(f);
            iv.setScaleY(f);
        }
    }

    /** 双击复位到 100% */
    private void resetZoom() {
        scaleFactor = 1f;
        applyScale(1f, true);
        showZoomIndicator(false);
    }

    /** 手势结束后平滑回到合法缩放范围（0.5x~3x 边界回弹） */
    private void animateToBounds() {
        float target = Math.max(0.5f, Math.min(3f, scaleFactor));
        if (target != scaleFactor) {
            scaleFactor = target;
            applyScale(target, true);
        }
    }

    /** 缩放比例提示：手势中常显，结束时延迟淡出 */
    private void showZoomIndicator(boolean show) {
        if (zoomIndicator == null) return;
        zoomIndicator.removeCallbacks(zoomFadeRunnable);
        if (show) {
            zoomIndicator.animate().cancel();
            zoomIndicator.setAlpha(1f);
            if (zooming) zoomIndicator.setText(Math.round(scaleFactor * 100) + "%");
        } else {
            zoomIndicator.postDelayed(zoomFadeRunnable, 600);
        }
    }

    private final Runnable zoomFadeRunnable = () -> {
        if (zoomIndicator != null && !zooming) {
            zoomIndicator.animate().alpha(0f).setDuration(250).start();
        }
    };

    private int dp(int v) {
        float d = getContext().getResources().getDisplayMetrics().density;
        return (int) (v * d + 0.5f);
    }

    // ===== 便捷入口 =====

    /** 单张图片预览（兼容旧接口） */
    public static void show(@Nullable Context ctx, String url, OkHttpClient client) {
        show(ctx, url, null, client);
    }

    /** 单张图片预览 + 单号（用于磁盘缓存兜底） */
    public static void show(@Nullable Context ctx, String url, String billCode, OkHttpClient client) {
        if (ctx == null) return;
        if (url == null || url.length() == 0) {
            Toast.makeText(ctx, "暂无图片", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            List<String> urls = new ArrayList<>();
            urls.add(url);
            List<String> nos = (billCode != null && billCode.length() > 0) 
                    ? java.util.Collections.singletonList(billCode) : null;
            ImagePreviewDialog d = new ImagePreviewDialog(ctx, urls, 0, nos, client, false);
            d.show();
        } catch (Throwable t) {
            Toast.makeText(ctx, "无法预览: " + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /** 多张图片预览：传入图片列表 + 对应单号列表 + 起始索引（跨包裹翻页） */
    public static void show(@Nullable Context ctx,
                            List<String> imageUrls,
                            int startIndex,
                            List<String> trackingNumbers,
                            OkHttpClient client) {
        if (ctx == null) return;
        if (imageUrls == null || imageUrls.isEmpty()) {
            Toast.makeText(ctx, "暂无图片", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            ImagePreviewDialog d = new ImagePreviewDialog(ctx, imageUrls, startIndex, trackingNumbers, client);
            d.show();
        } catch (Throwable t) {
            Toast.makeText(ctx, "无法预览: " + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
}
