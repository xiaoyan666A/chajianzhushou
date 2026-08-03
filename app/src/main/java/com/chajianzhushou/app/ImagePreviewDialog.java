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
    private final List<String> compareUrls;   // 与 imageUrls 平行："另一张照片"URL，空串=无
    private final List<String> compareNames;  // 平行名称（入库照/出库照）
    private final List<String> primaryNames;  // 平行名称：当前显示照片的名称（入库照/出库照）
    private final OkHttpClient httpClient;
    private int currentIndex;

    private ImageView iv;
    private ProgressBar pb;
    private TextView tvTitle;
    private View btnPrev;
    private View btnNext;
    private final boolean showNavButtons;

    // Matrix 缩放/平移：双指捏合（以手指焦点为锚点，1x~3x）+ 单指拖动 + 边界钳制
    private final android.graphics.Matrix imageMatrix = new android.graphics.Matrix();
    private float fitScale = 1f;      // 图片适配视图的基础缩放
    private float currentScale = 1f;  // 当前相对基础缩放的倍数（1x=适配满屏）
    private boolean zooming = false;  // 捏合进行中
    private boolean imageMatrixReady = false;
    private int bitmapW = 0, bitmapH = 0;
    private boolean dragging = false;
    private boolean dragMoved = false;
    private float downX = 0f, downY = 0f;
    private float lastDragX = 0f, lastDragY = 0f;
    private ScaleGestureDetector scaleDetector;
    private GestureDetector doubleTapDetector;
    private TextView zoomIndicator;
    private TextView btnCompare;
    private boolean showingCompare = false;
    // 签名 URL 过期时允许"重新解析并重试"的次数上限（防极端情况下无限重试）
    private int urlRefreshBudget = 2;

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
            showingCompare = false; // 原图已更新，回到原图视图
            loadCurrentImage();
        }
    }

    public ImagePreviewDialog(@NonNull Context context,
                              List<String> imageUrls,
                              int startIndex,
                              List<String> trackingNumbers,
                              OkHttpClient httpClient) {
        this(context, imageUrls, startIndex, trackingNumbers, httpClient, true, null, null, null);
    }

    public ImagePreviewDialog(@NonNull Context context,
                              List<String> imageUrls,
                              int startIndex,
                              List<String> trackingNumbers,
                              OkHttpClient httpClient,
                              boolean showNavButtons) {
        this(context, imageUrls, startIndex, trackingNumbers, httpClient, showNavButtons, null, null, null);
    }

    public ImagePreviewDialog(@NonNull Context context,
                              List<String> imageUrls,
                              int startIndex,
                              List<String> trackingNumbers,
                              OkHttpClient httpClient,
                              boolean showNavButtons,
                              List<String> compareUrls,
                              List<String> compareNames) {
        this(context, imageUrls, startIndex, trackingNumbers, httpClient, showNavButtons,
                compareUrls, compareNames, null);
    }

    public ImagePreviewDialog(@NonNull Context context,
                              List<String> imageUrls,
                              int startIndex,
                              List<String> trackingNumbers,
                              OkHttpClient httpClient,
                              boolean showNavButtons,
                              List<String> compareUrls,
                              List<String> compareNames,
                              List<String> primaryNames) {
        super(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        this.imageUrls = (imageUrls == null) ? new ArrayList<>() : imageUrls;
        this.trackingNumbers = (trackingNumbers == null) ? new ArrayList<>() : trackingNumbers;
        this.compareUrls = (compareUrls == null) ? new ArrayList<>() : compareUrls;
        this.compareNames = (compareNames == null) ? new ArrayList<>() : compareNames;
        this.primaryNames = (primaryNames == null) ? new ArrayList<>() : primaryNames;
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
        // 使用 Matrix 变换实现缩放/平移（FIT_CENTER 无法拖动，且视图缩放易抖动）
        iv.setScaleType(ImageView.ScaleType.MATRIX);
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
        close.setTextSize(20f);
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
        tvTitle.setTextSize(17f);
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
        ((TextView) btnPrev).setTextSize(24f);
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
            showingCompare = false;
            loadCurrentImage();
        });

        btnNext = new TextView(getContext());
        ((TextView) btnNext).setText("▶");
        ((TextView) btnNext).setTextColor(0xFFFFFFFF);
        ((TextView) btnNext).setTextSize(24f);
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
            showingCompare = false;
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
        zoomIndicator.setTextSize(15f);
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

        // ===== 出入库照片对比按钮（底部左侧，有对比照片时显示） =====
        btnCompare = new TextView(getContext());
        btnCompare.setText("对比照片");
        btnCompare.setTextColor(0xFFFFFFFF);
        btnCompare.setTextSize(15f);
        btnCompare.setTypeface(Typeface.DEFAULT_BOLD);
        btnCompare.setGravity(Gravity.CENTER);
        GradientDrawable cmpBg = new GradientDrawable();
        cmpBg.setShape(GradientDrawable.RECTANGLE);
        cmpBg.setCornerRadius(dp(14));
        cmpBg.setColor(0x99000000);
        cmpBg.setStroke(dp(1), 0x66FFFFFF);
        btnCompare.setBackground(cmpBg);
        btnCompare.setPadding(dp(16), dp(8), dp(16), dp(8));
        btnCompare.setVisibility(View.GONE);
        FrameLayout.LayoutParams cmpLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.START | Gravity.BOTTOM);
        cmpLp.setMargins(dp(16), 0, 0, dp(30));
        btnCompare.setLayoutParams(cmpLp);
        btnCompare.setOnClickListener(v -> toggleCompare());
        root.addView(btnCompare);

        scaleDetector = new ScaleGestureDetector(getContext(), new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector detector) {
                // 注意：不能在这里调用 ensureBaseMatrix()（它会重置缩放矩阵），
                // 否则第二次捏合会从 1x 重新开始，缩小也会直接回到原比例。
                // 基础矩阵在图片加载完成/布局尺寸变化时已经建立好。
                zooming = true;
                showZoomIndicator(true);
                return imageMatrixReady;
            }
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                if (!imageMatrixReady) return false;
                // 边界限制：缩放钳制在 1x~3x（相对适配比例）；
                // 以双指焦点为锚点缩放，避免中心缩放导致图片相对手指抖动
                float factor = detector.getScaleFactor();
                float next = Math.max(1f, Math.min(3f, currentScale * factor));
                float applied = next / currentScale;
                if (applied != 1f) {
                    currentScale = next;
                    imageMatrix.postScale(applied, applied, detector.getFocusX(), detector.getFocusY());
                    iv.setImageMatrix(imageMatrix);
                    if (zoomIndicator != null) zoomIndicator.setText(Math.round(currentScale * 100) + "%");
                }
                return true;
            }
            @Override
            public void onScaleEnd(ScaleGestureDetector detector) {
                zooming = false;
                clampMatrix();
                showZoomIndicator(false);
            }
        });
        doubleTapDetector = new GestureDetector(getContext(), new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                animateReset();
                return true;
            }
        });
        iv.setOnTouchListener((v, event) -> {
            if (scaleDetector != null) scaleDetector.onTouchEvent(event);
            if (doubleTapDetector != null) doubleTapDetector.onTouchEvent(event);
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    dragging = true;
                    dragMoved = false;
                    downX = event.getX();
                    downY = event.getY();
                    lastDragX = downX;
                    lastDragY = downY;
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                    // 第二根手指按下：取消单指拖动，交给捏合缩放
                    dragging = false;
                    break;
                case MotionEvent.ACTION_MOVE:
                    // 放大后才能拖动图片
                    if (dragging && !zooming && currentScale > 1.01f && imageMatrixReady) {
                        if (!dragMoved) {
                            int slop = android.view.ViewConfiguration.get(getContext()).getScaledTouchSlop();
                            if (Math.abs(event.getX() - downX) < slop && Math.abs(event.getY() - downY) < slop) break;
                            dragMoved = true;
                        }
                        float dx = event.getX() - lastDragX;
                        float dy = event.getY() - lastDragY;
                        if (dx != 0f || dy != 0f) {
                            imageMatrix.postTranslate(dx, dy);
                            iv.setImageMatrix(imageMatrix);
                            lastDragX = event.getX();
                            lastDragY = event.getY();
                        }
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (dragMoved) clampMatrix();
                    dragging = false;
                    dragMoved = false;
                    break;
            }
            // 捏合/拖动进行中或双指按下时消费事件，普通单击放行（保留点击关闭）
            return zooming || (dragging && dragMoved) || event.getPointerCount() >= 2;
        });
        // 布局尺寸变化时重建基础矩阵（Matrix 缩放与布局无关，无需 pivot）
        iv.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int w = r - l;
            int h = b - t;
            int ow = or - ol;
            int oh = ob - ot;
            if (w > 0 && h > 0 && (w != ow || h != oh)) {
                ensureBaseMatrix();
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
        imageMatrixReady = false;
        currentScale = 1f;
        if (iv != null) {
            iv.setImageMatrix(new android.graphics.Matrix());
        }
        if (zoomIndicator != null) {
            zoomIndicator.removeCallbacks(zoomFadeRunnable);
            zoomIndicator.setAlpha(0f);
        }
        updateCompareButton();

        String url = showingCompare ? compareUrl(currentIndex) : imageUrls.get(currentIndex);
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

        // 磁盘缓存 key：主图=单号；对比图="单号_cmp"（分开缓存，避免与主图互相覆盖）
        final String cacheKey = (trackingNo != null && trackingNo.length() > 0)
                ? (showingCompare ? trackingNo + "_cmp" : trackingNo) : "";

        // 1) 优先查磁盘缓存（主图/对比图各自独立缓存；URL 校验：出库换新照片后旧图自动作废）
        if (cacheKey.length() > 0) {
            java.io.File diskFile = ImageCacheManager.getCachedFile(cacheKey, url);
            if (diskFile != null) {
                try {
                    Bitmap bmp = BitmapFactory.decodeFile(diskFile.getAbsolutePath());
                    if (bmp != null && !bmp.isRecycled()) {
                        if (pb != null) pb.setVisibility(View.GONE);
                        if (iv != null) {
                            iv.setImageBitmap(bmp);
                            ensureBaseMatrix();
                        }
                        return;
                    }
                    // 解码失败 → 磁盘缓存文件可能损坏，删除它以允许后续重新下载
                    Log.w("ImgPreview", "磁盘缓存解码失败，删除损坏文件: " + cacheKey);
                    diskFile.delete();
                } catch (Exception e) {
                    Log.w("ImgPreview", "磁盘缓存解码异常: " + cacheKey + " " + e.getMessage());
                    try { diskFile.delete(); } catch (Exception ignored) {}
                }
            }
        }

        // 2) 内存缓存
        ImageLoader loader = ImageLoader.with(httpClient);
        Bitmap cached = loader.getCachedBitmap(url);
        if (cached != null) {
            if (pb != null) pb.setVisibility(View.GONE);
            if (iv != null) {
                iv.setImageBitmap(cached);
                ensureBaseMatrix();
            }
            return;
        }
        // 3) 网络下载兜底（对比图也写入磁盘缓存，签名 URL 过期后仍可从缓存显示）
        final String fallbackCacheKey = cacheKey;
        loader.loadFull(url, iv, 0, bitmap -> {
            if (pb != null) pb.setVisibility(View.GONE);
            if (bitmap != null) ensureBaseMatrix();
            if (bitmap == null) {
                // 网络下载失败（URL 可能已过期）：延迟 2 秒重查一次磁盘缓存
                // （场景：缩略图首次下载尚未完成时用户就点击了放大）；
                // 磁盘也没有 → 签名 URL 已过期，重新解析一个新 URL 再重试
                final Runnable expiredAction = () -> tryRetryWithFreshUrl(currentIndex, url, showingCompare);
                if (fallbackCacheKey.length() > 0) {
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                        java.io.File retryFile = ImageCacheManager.getCachedFile(fallbackCacheKey, url);
                        if (retryFile != null) {
                            try {
                                Bitmap retryBmp = BitmapFactory.decodeFile(retryFile.getAbsolutePath());
                                if (retryBmp != null && !retryBmp.isRecycled() && iv != null) {
                                    iv.setImageBitmap(retryBmp);
                                    ensureBaseMatrix();
                                    return;
                                }
                            } catch (Exception ignored) {}
                        }
                        expiredAction.run();
                    }, 2000);
                } else {
                    expiredAction.run();
                }
            }
        }, cacheKey.length() > 0 ? cacheKey : null);
    }

    /**
     * 签名 URL 疑似过期：从 URL 中提取原始文件路径（remoteFileId）重新解析一次并重试。
     * 带次数上限（urlRefreshBudget），防止极端情况下无限重试。
     */
    private void tryRetryWithFreshUrl(final int idx, final String oldUrl, final boolean compare) {
        if (urlRefreshBudget <= 0) {
            try { Toast.makeText(getContext(), "图片加载失败，请稍后重试", Toast.LENGTH_SHORT).show(); } catch (Throwable ignored) {}
            return;
        }
        urlRefreshBudget--;
        try {
            final Context c = getContext();
            if (c == null) return;
            final String rawPath = extractRemoteFileId(oldUrl);
            final String billNo = (trackingNumbers != null && idx >= 0 && idx < trackingNumbers.size())
                    ? trackingNumbers.get(idx) : "";
            if (rawPath.isEmpty() || billNo.isEmpty()) {
                try { Toast.makeText(c, "图片加载失败，请稍后重试", Toast.LENGTH_SHORT).show(); } catch (Throwable ignored) {}
                return;
            }
            DirectApiClient dac = new DirectApiClient(c.getApplicationContext());
            dac.resolveImageUrl(billNo, rawPath, new DirectApiClient.ImageUrlCallback() {
                @Override
                public void onUrl(final String newUrl) {
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                        if (newUrl == null || newUrl.length() == 0 || newUrl.equals(oldUrl)) {
                            try { Toast.makeText(getContext(), "图片加载失败，请稍后重试", Toast.LENGTH_SHORT).show(); } catch (Throwable ignored) {}
                            return;
                        }
                        try {
                            if (compare) {
                                if (compareUrls != null && idx >= 0 && idx < compareUrls.size()) compareUrls.set(idx, newUrl);
                            } else if (imageUrls != null && idx >= 0 && idx < imageUrls.size()) {
                                imageUrls.set(idx, newUrl);
                            }
                            showingCompare = compare;
                            loadCurrentImage();
                        } catch (Throwable ignored) {}
                    });
                }

                @Override
                public void onError(String error) {
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                        try { Toast.makeText(getContext(), "图片加载失败，请稍后重试", Toast.LENGTH_SHORT).show(); } catch (Throwable ignored) {}
                    });
                }
            });
        } catch (Throwable t) {
            try { Toast.makeText(getContext(), "图片加载失败，请稍后重试", Toast.LENGTH_SHORT).show(); } catch (Throwable ignored) {}
        }
    }

    /** 从 fs.zto.com / kdcs-file-storage 的签名 URL 中提取 remoteFileId（S3 原始路径），供过期后重新解析 */
    private static String extractRemoteFileId(String url) {
        try {
            if (url == null || url.isEmpty()) return "";
            int qi = url.indexOf('?');
            if (qi < 0) return "";
            String data = null;
            for (String pair : url.substring(qi + 1).split("&")) {
                int ei = pair.indexOf('=');
                if (ei > 0 && "data".equals(pair.substring(0, ei))) {
                    data = java.net.URLDecoder.decode(pair.substring(ei + 1), "UTF-8");
                    break;
                }
            }
            if (data == null || data.isEmpty()) return "";
            return new org.json.JSONObject(data).optString("remoteFileId", "");
        } catch (Throwable t) {
            return "";
        }
    }

    // ===== Matrix 缩放/平移辅助 =====

    /** 以基础矩阵重建：按 fitScale*scale 缩放并居中（scale=1 即适配满屏） */
    private void rebuildToBase(float scale) {
        if (iv == null || bitmapW <= 0 || bitmapH <= 0) return;
        float dispW = bitmapW * fitScale * scale;
        float dispH = bitmapH * fitScale * scale;
        imageMatrix.reset();
        imageMatrix.postScale(fitScale * scale, fitScale * scale);
        imageMatrix.postTranslate((iv.getWidth() - dispW) / 2f, (iv.getHeight() - dispH) / 2f);
        iv.setImageMatrix(imageMatrix);
    }

    /** 根据当前 Drawable/视图尺寸计算基础缩放并重置变换（切换图片或尺寸变化时调用） */
    private void ensureBaseMatrix() {
        if (iv == null) return;
        android.graphics.drawable.Drawable d = iv.getDrawable();
        int vw = iv.getWidth();
        int vh = iv.getHeight();
        if (d == null || vw <= 0 || vh <= 0) {
            imageMatrixReady = false;
            return;
        }
        int iw = d.getIntrinsicWidth();
        int ih = d.getIntrinsicHeight();
        if (iw <= 0 || ih <= 0) {
            imageMatrixReady = false;
            return;
        }
        bitmapW = iw;
        bitmapH = ih;
        fitScale = Math.min((float) vw / iw, (float) vh / ih);
        currentScale = 1f;
        imageMatrixReady = true;
        rebuildToBase(1f);
    }

    /** 平移边界钳制：图片某边小于视图时锁居中；大于视图时不允许拖出空白 */
    private void clampMatrix() {
        if (!imageMatrixReady || iv == null) return;
        float[] v = new float[9];
        imageMatrix.getValues(v);
        float sx = v[android.graphics.Matrix.MSCALE_X];
        float tx = v[android.graphics.Matrix.MTRANS_X];
        float ty = v[android.graphics.Matrix.MTRANS_Y];
        float dispW = bitmapW * sx;
        float dispH = bitmapH * sx;
        float vw = iv.getWidth();
        float vh = iv.getHeight();
        float newTx = tx;
        float newTy = ty;
        if (dispW <= vw) newTx = (vw - dispW) / 2f;
        else newTx = Math.max(vw - dispW, Math.min(0f, tx));
        if (dispH <= vh) newTy = (vh - dispH) / 2f;
        else newTy = Math.max(vh - dispH, Math.min(0f, ty));
        if (newTx != tx || newTy != ty) {
            imageMatrix.postTranslate(newTx - tx, newTy - ty);
            iv.setImageMatrix(imageMatrix);
        }
    }

    /** 双击复位：平滑回到适配比例 */
    private void animateReset() {
        if (!imageMatrixReady || iv == null) return;
        final float start = currentScale;
        android.animation.ValueAnimator anim = android.animation.ValueAnimator.ofFloat(start, 1f);
        anim.setDuration(180);
        anim.addUpdateListener(a -> {
            currentScale = (float) a.getAnimatedValue();
            rebuildToBase(currentScale);
        });
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                currentScale = 1f;
                rebuildToBase(1f);
                showZoomIndicator(false);
            }
        });
        anim.start();
    }

    /** 缩放比例提示：手势中常显，结束时延迟淡出 */
    private void showZoomIndicator(boolean show) {
        if (zoomIndicator == null) return;
        zoomIndicator.removeCallbacks(zoomFadeRunnable);
        if (show) {
            zoomIndicator.animate().cancel();
            zoomIndicator.setAlpha(1f);
            if (zooming) zoomIndicator.setText(Math.round(currentScale * 100) + "%");
        } else {
            zoomIndicator.postDelayed(zoomFadeRunnable, 600);
        }
    }

    private final Runnable zoomFadeRunnable = () -> {
        if (zoomIndicator != null && !zooming) {
            zoomIndicator.animate().alpha(0f).setDuration(250).start();
        }
    };

    // ===== 出入库照片对比 =====

    private String compareUrl(int idx) {
        if (compareUrls == null || idx < 0 || idx >= compareUrls.size()) return "";
        String u = compareUrls.get(idx);
        return (u == null) ? "" : u;
    }

    private String compareName(int idx) {
        if (compareNames == null || idx < 0 || idx >= compareNames.size()) return "对比照片";
        String n = compareNames.get(idx);
        return (n == null || n.isEmpty()) ? "对比照片" : n;
    }

    private String primaryName(int idx) {
        if (primaryNames == null || idx < 0 || idx >= primaryNames.size()) return "原图";
        String n = primaryNames.get(idx);
        return (n == null || n.isEmpty()) ? "原图" : n;
    }

    /** 根据当前页是否有对比照片，显示/隐藏切换按钮并更新文案 */
    private void updateCompareButton() {
        if (btnCompare == null) return;
        String cmp = compareUrl(currentIndex);
        if (cmp.isEmpty()) {
            btnCompare.setVisibility(View.GONE);
            return;
        }
        btnCompare.setVisibility(View.VISIBLE);
        // 按钮文案按"当前显示的是哪张图"动态变化：
        // 当前显示入库图 → "点击查看出库图片"；显示的是出库图 → "点击查看入库图片"；
        // 切换后同理显示"返回查看XX图片"。两张图都缺时不出现切换按钮。
        btnCompare.setText(showingCompare
                ? "返回查看" + primaryName(currentIndex)
                : "点击查看" + compareName(currentIndex));
    }

    /** 点击切换：原图 ↔ 对比照片（入库照/出库照） */
    private void toggleCompare() {
        String cmp = compareUrl(currentIndex);
        if (cmp.isEmpty()) return;
        String primary = "";
        try { primary = imageUrls.get(currentIndex); } catch (Throwable ignore) {}
        // 记录切换日志：核对两张图分别是什么
        try {
            LogRecorder.info(getContext(), "IMAGE", "预览对比切换",
                    "primary=" + primary + " compare=" + cmp
                            + " name=" + compareName(currentIndex));
        } catch (Exception ignore) {}
        if (cmp.equals(primary)) return; // 两张图相同：不切换
        showingCompare = !showingCompare;
        loadCurrentImage();
    }

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

    /** 多张图片预览 + 出入库照片对比列表（compareUrls/compareNames 与 imageUrls 顺序一致，空串=无对比图） */
    public static void show(@Nullable Context ctx,
                            List<String> urls,
                            int startIndex,
                            List<String> trackingNumbers,
                            OkHttpClient client,
                            List<String> compareUrls,
                            List<String> compareNames) {
        show(ctx, urls, startIndex, trackingNumbers, client, compareUrls, compareNames, null);
    }

    /** 多张图片预览 + 出入库照片对比（含当前显示照片的名称，用于切换按钮文案） */
    public static void show(@Nullable Context ctx,
                            List<String> urls,
                            int startIndex,
                            List<String> trackingNumbers,
                            OkHttpClient client,
                            List<String> compareUrls,
                            List<String> compareNames,
                            List<String> primaryNames) {
        if (ctx == null || urls == null || urls.isEmpty()) {
            if (ctx != null) Toast.makeText(ctx, "暂无图片", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            ImagePreviewDialog d = new ImagePreviewDialog(
                    ctx, urls, startIndex, trackingNumbers, client, true,
                    compareUrls, compareNames, primaryNames);
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
