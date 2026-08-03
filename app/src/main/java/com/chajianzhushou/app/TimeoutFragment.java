package com.chajianzhushou.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class TimeoutFragment extends Fragment {
    private static final String TAG = "TimeoutFragment";

    // 按单号缓存按 billCode 查询的完整结果（含 imageUrl/courier/recipient/arrivedAt 等），避免重复请求
    private static final ConcurrentHashMap<String, JSONObject> sBillPackageCache = new ConcurrentHashMap<>();

    // Views
    private TextView tvCountdown;
    private TextView tvTimeoutCount;
    private Button btnRefresh;
    private LinearLayout timeoutListContainer;
    private LinearLayout tvTimeoutEmpty;
    private ProgressBar progressBar;
    private FrameLayout loadingMask;

    // Core
    private ApiService apiService;
    private DirectApiClient directApiClient;
    private SyncClient syncClient;

    // State
    private Set<String> outboundDoneBillCodes = new HashSet<>();
    private volatile boolean isViewReady = false;
    private boolean serverConnectEnabled = false;
    private volatile boolean isQuerying = false;
    private volatile long lastQueryAt = 0;
    // 图片预览：所有非空图片 URL + 对应单号（跨包裹上下张翻页）
    private final List<String> timeoutAllImageUrls = new ArrayList<>();
    private final List<String> timeoutAllNos = new ArrayList<>();

    // Countdown (display only — auto-outbound is executed by PC)
    private CountDownTimer countDownTimer;
    private int autoOutboundHour = -1;
    private int autoOutboundMinute = -1;
    private boolean autoOutboundEnabled = false;
    private boolean localAudioEnabled = true;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_timeout, container, false);

        tvCountdown = view.findViewById(R.id.tv_countdown);
        tvTimeoutCount = view.findViewById(R.id.tv_timeout_count);
        btnRefresh = view.findViewById(R.id.btn_refresh);
        timeoutListContainer = view.findViewById(R.id.timeout_list_container);
        tvTimeoutEmpty = view.findViewById(R.id.tv_timeout_empty);
        progressBar = view.findViewById(R.id.progress_bar);
        loadingMask = view.findViewById(R.id.loading_mask);

        apiService = new ApiService(requireContext());
        directApiClient = new DirectApiClient(requireContext());
        isViewReady = true;

        // Check server connection preference
        try {
            SharedPreferences prefs = requireContext().getSharedPreferences("chajianzhushou_prefs", Context.MODE_PRIVATE);
            serverConnectEnabled = prefs.getBoolean("server_connect_enabled", false);
        } catch (Exception ignore) {}

        if (btnRefresh != null) {
            btnRefresh.setOnClickListener(v -> loadTimeoutPackages());
        }

        // Load settings from server (for countdown display) or show default
        if (serverConnectEnabled) {
            // Connect SSE to receive real-time countdown settings changes from PC
            syncClient = new SyncClient(apiService);
            syncClient.setCallback(new SyncClient.SyncCallback() {
                @Override public void onQueryInputReceived(String value) {}
                @Override public void onQueryTriggerReceived(String billCode, String type) {}
                @Override public void onGridViewChanged(boolean gridView) {}
                @Override public void onShowDeliveredChanged(boolean showDelivered) {}
                @Override public void onConnected() {}
                @Override public void onDisconnected() {}
                @Override public void onError(String error) {}
                @Override
                public void onSettingsChanged(JSONObject settings) {
                    if (!isViewReady) return;
                    try {
                        autoOutboundEnabled = settings.optBoolean("autoOutboundEnabled", autoOutboundEnabled);
                        if (settings.has("autoOutboundHour")) autoOutboundHour = settings.optInt("autoOutboundHour", -1);
                        if (settings.has("autoOutboundMinute")) autoOutboundMinute = settings.optInt("autoOutboundMinute", -1);
                        Log.d(TAG, "SSE设置变更: hour=" + autoOutboundHour + " minute=" + autoOutboundMinute + " enabled=" + autoOutboundEnabled);
                        try { LogRecorder.info(requireContext(), "Timeout", "SSE设置变更", "hour=" + autoOutboundHour + " minute=" + autoOutboundMinute + " enabled=" + autoOutboundEnabled); } catch (Exception ignore) {}
                        startCountdown();
                    } catch (Exception ignore) {}
                }
            });
            syncClient.connect();
            loadSettings();
        } else {
            if (tvCountdown != null) tvCountdown.setText("距离自动出库: --:--:--");
            loadTimeoutPackages();
        }

        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        // 每次回到页面时重读取服务器连接偏好，避免与设置界面变更不同步
        try {
            SharedPreferences prefs = requireContext().getSharedPreferences("chajianzhushou_prefs", Context.MODE_PRIVATE);
            serverConnectEnabled = prefs.getBoolean("server_connect_enabled", false);
        } catch (Exception ignore) {}
        if (isAdded()) loadTimeoutPackages();
    }

    @Override
    public void onDestroyView() {
        isViewReady = false;
        if (countDownTimer != null) {
            try { countDownTimer.cancel(); } catch (Exception ignore) {}
            countDownTimer = null;
        }
        if (syncClient != null) {
            try { syncClient.disconnect(); } catch (Exception ignore) {}
            syncClient = null;
        }
        super.onDestroyView();

        tvCountdown = null;
        tvTimeoutCount = null;
        btnRefresh = null;
        timeoutListContainer = null;
        tvTimeoutEmpty = null;
        progressBar = null;
        apiService = null;
    }

    // ===== Helpers =====

    private void safeToast(String msg) {
        if (!isAdded() || !isViewReady) return;
        Context ctx = getContext();
        if (ctx == null) return;
        try { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show(); } catch (Exception ignore) {}
    }

    // ===== Settings (from server, for countdown display only) =====

    private void loadSettings() {
        apiService.getSettings(new ApiService.ApiCallback() {
            @Override
            public void onSuccess(JSONObject response) {
                if (!isAdded() || !isViewReady) return;
                try {
                    JSONObject data = null;
                    if (response.has("data") && !response.isNull("data")) {
                        Object d = response.get("data");
                        if (d instanceof JSONObject) data = (JSONObject) d;
                    }
                    JSONObject src = (data != null) ? data : response;

                    autoOutboundEnabled = src.optBoolean("autoOutboundEnabled", false);
                    autoOutboundHour = src.optInt("autoOutboundHour", -1);
                    autoOutboundMinute = src.optInt("autoOutboundMinute", -1);
                    localAudioEnabled = src.optBoolean("localAudioEnabled", true);

                    Log.d(TAG, "加载设置成功: hour=" + autoOutboundHour + " minute=" + autoOutboundMinute + " enabled=" + autoOutboundEnabled);
                    try { LogRecorder.info(requireContext(), "Timeout", "加载设置成功", "hour=" + autoOutboundHour + " minute=" + autoOutboundMinute); } catch (Exception ignore) {}
                    startCountdown();
                    loadTimeoutPackages();
                } catch (Exception e) {
                    if (tvCountdown != null) {
                        try { tvCountdown.setText("无法获取设置"); } catch (Exception ignore) {}
                    }
                    loadTimeoutPackages();
                }
            }

            @Override
            public void onError(String error) {
                if (!isViewReady || tvCountdown == null) return;
                try { tvCountdown.setText("距离自动出库: --:--:--"); } catch (Exception ignore) {}
                loadTimeoutPackages();
            }
        });
    }

    // ===== Countdown (display only) =====

    private void startCountdown() {
        if (!isViewReady) return;
        if (countDownTimer != null) {
            try { countDownTimer.cancel(); } catch (Exception ignore) {}
            countDownTimer = null;
        }

        // 校验 hour/minute 范围（SSE 或服务器可能下发 -1/越界值，直接用会算出错误目标时间）
        boolean hourOk = autoOutboundHour >= 0 && autoOutboundHour <= 23;
        boolean minuteOk = autoOutboundMinute >= 0 && autoOutboundMinute <= 59;
        if (!autoOutboundEnabled || !hourOk || !minuteOk) {
            if (tvCountdown != null) {
                try { tvCountdown.setText("距离自动出库: 未开启"); } catch (Exception ignore) {}
            }
            Log.d(TAG, "倒计时未开启: enabled=" + autoOutboundEnabled + " hour=" + autoOutboundHour + " minute=" + autoOutboundMinute);
            try { LogRecorder.info(requireContext(), "Timeout", "倒计时未开启", "enabled=" + autoOutboundEnabled + " hour=" + autoOutboundHour + " minute=" + autoOutboundMinute); } catch (Exception ignore) {}
            return;
        }

        Log.d(TAG, "启动倒计时: " + autoOutboundHour + ":" + String.format("%02d", autoOutboundMinute));
        try { LogRecorder.info(requireContext(), "Timeout", "启动倒计时", autoOutboundHour + ":" + String.format("%02d", autoOutboundMinute)); } catch (Exception ignore) {}
        Calendar now = Calendar.getInstance();
        Calendar target = Calendar.getInstance();
        target.set(Calendar.HOUR_OF_DAY, autoOutboundHour);
        target.set(Calendar.MINUTE, autoOutboundMinute);
        target.set(Calendar.SECOND, 0);
        target.set(Calendar.MILLISECOND, 0);

        if (target.before(now)) {
            target.add(Calendar.DAY_OF_MONTH, 1);
        }

        long millisUntilTarget = target.getTimeInMillis() - now.getTimeInMillis();
        if (millisUntilTarget <= 0) millisUntilTarget = 1000;

        countDownTimer = new CountDownTimer(millisUntilTarget, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                if (!isViewReady || tvCountdown == null) {
                    try { cancel(); } catch (Exception ignore) {}
                    return;
                }
                try {
                    long hours = millisUntilFinished / (1000 * 60 * 60);
                    long minutes = (millisUntilFinished % (1000 * 60 * 60)) / (1000 * 60);
                    long seconds = (millisUntilFinished % (1000 * 60)) / 1000;
                    tvCountdown.setText(String.format(Locale.getDefault(),
                            "距离自动出库: %02d:%02d:%02d", hours, minutes, seconds));
                } catch (Exception e) {}
            }

            @Override
            public void onFinish() {
                if (!isViewReady) return;
                // Don't execute auto-outbound — handled by PC. Just restart countdown.
                startCountdown();
                if (tvCountdown != null) {
                    try { tvCountdown.setText("距离自动出库: 正在出库..."); } catch (Exception ignore) {}
                }
            }
        };
        try {
            countDownTimer.start();
        } catch (Exception e) {
            if (tvCountdown != null) {
                try { tvCountdown.setText("距离自动出库: --:--:--"); } catch (Exception ignore) {}
            }
        }
    }

    // ===== Timeout Packages =====

    private void loadTimeoutPackages() {
        if (!isAdded() || !isViewReady) return;
        long now = System.currentTimeMillis();
        if (isQuerying) {
            Log.d(TAG, "加载超时件列表被跳过：正在查询中");
            return;
        }
        if (now - lastQueryAt < 400) {
            Log.d(TAG, "加载超时件列表被跳过：防抖冷却期");
            return;
        }
        lastQueryAt = now;
        isQuerying = true;

        // 每次查询时重读取服务器连接偏好，保证最新状态
        try {
            SharedPreferences prefs = requireContext().getSharedPreferences("chajianzhushou_prefs", Context.MODE_PRIVATE);
            serverConnectEnabled = prefs.getBoolean("server_connect_enabled", false);
        } catch (Exception ignore) {}

        Log.d(TAG, "加载超时件列表: serverConnect=" + serverConnectEnabled);
        try { LogRecorder.info(requireContext(), "Timeout", "加载超时件列表", "serverConnect=" + serverConnectEnabled); } catch (Exception ignore) {}
        showLoading(true);

        if (serverConnectEnabled) {
            // Server mode: proxy through PC
            apiService.getTimeoutPackages(new ApiService.ApiArrayCallback() {
                @Override
                public void onSuccess(JSONArray response) {
                    isQuerying = false;
                    if (!isViewReady) return;
                    showLoading(false);
                    renderTimeoutList(response);
                }

                @Override
                public void onError(String error) {
                    isQuerying = false;
                    if (!isViewReady) return;
                    showLoading(false);
                    safeToast("加载失败: " + error);
                }
            });
        } else {
            // Direct mode: call ZTO API directly
            new Thread(() -> {
                try {
                    JSONArray response = directApiClient.queryTimeoutPackages();
                    if (!isViewReady) { isQuerying = false; return; }
                    requireActivity().runOnUiThread(() -> {
                        isQuerying = false;
                        showLoading(false);
                        renderTimeoutList(response);
                    });
                } catch (Exception e) {
                    if (!isViewReady) { isQuerying = false; return; }
                    requireActivity().runOnUiThread(() -> {
                        isQuerying = false;
                        showLoading(false);
                        safeToast("加载失败: " + e.getMessage());
                    });
                }
            }).start();
        }
    }

    private void showLoading(boolean show) {
        if (!isViewReady) return;
        try {
            if (loadingMask != null) {
                // 存在全屏遮罩时：只显示遮罩，隐藏底部 ProgressBar（避免“两个 loading”同时显示）
                loadingMask.setVisibility(show ? View.VISIBLE : View.GONE);
                if (progressBar != null) progressBar.setVisibility(View.GONE);
            } else {
                // 遮罩不可用时回退使用底部 ProgressBar
                if (progressBar != null) progressBar.setVisibility(show ? View.VISIBLE : View.GONE);
            }
        } catch (Exception ignore) {}
    }

    private void renderTimeoutList(JSONArray packages) {
        if (!isViewReady || timeoutListContainer == null || tvTimeoutEmpty == null || tvTimeoutCount == null) return;

        try {
            timeoutListContainer.removeAllViews();
            tvTimeoutEmpty.setVisibility(View.GONE);

            int count = packages == null ? 0 : packages.length();
            tvTimeoutCount.setText(count + " 件");

            Log.d(TAG, "渲染超时件列表: " + count + " 件");
            try { LogRecorder.info(requireContext(), "Timeout", "渲染超时件列表", count + " 件"); } catch (Exception ignore) {}

            if (packages == null || count == 0) {
                synchronized (timeoutAllImageUrls) { timeoutAllImageUrls.clear(); timeoutAllNos.clear(); }
                tvTimeoutEmpty.setVisibility(View.VISIBLE);
                return;
            }

            // 构建全量图片/单号列表（跨包裹翻页用），按最终 resolve 后的 URL 收集
            synchronized (timeoutAllImageUrls) {
                timeoutAllImageUrls.clear();
                timeoutAllNos.clear();
                for (int i = 0; i < packages.length(); i++) {
                    JSONObject item = packages.optJSONObject(i);
                    if (item == null) continue;
                    String tno = firstNonEmpty(
                            item.optString("billCode", ""),
                            item.optString("trackingNumber", ""),
                            item.optString("waybillCode", ""),
                            nestedString(item, "bill", "billCode"),
                            nestedString(item, "bill", "trackingNumber"));
                    String raw = firstNonEmpty(
                            item.optString("imageUrl", ""),
                            item.optString("imgUrl", ""),
                            item.optString("picture", ""),
                            item.optString("pic", ""),
                            item.optString("photo", ""),
                            nestedString(item, "bill", "imageUrl"),
                            nestedString(item, "bill", "imgUrl"),
                            nestedString(item, "pkg", "imageUrl"),
                            nestedString(item, "packageInfo", "imageUrl"),
                            nestedString(item, "package", "imageUrl"),
                            nestedString(item, "info", "imageUrl"),
                            nestedString(item, "data", "imageUrl"));
                    if (raw.length() == 0) continue;
                    String resolved = (apiService != null) ? apiService.resolveImageUrl(raw) : raw;
                    if (resolved == null || resolved.length() == 0) continue;
                    timeoutAllImageUrls.add(resolved);
                    timeoutAllNos.add(tno);
                }
            }

            for (int i = 0; i < packages.length(); i++) {
                try {
                    JSONObject item = packages.getJSONObject(i);
                    addTimeoutCard(item);
                } catch (Exception e) {
                    // skip bad items
                }
            }
        } catch (Exception e) {
            safeToast("显示列表失败");
        }
    }

    private void addTimeoutCard(JSONObject item) {
        if (!isViewReady || timeoutListContainer == null) return;
        Context ctx = getContext();
        if (ctx == null) return;

        try {
            // 调试日志：打印当前 item 的所有 key，方便排查嵌套结构
            try {
                StringBuilder keys = new StringBuilder();
                if (item != null) {
                    java.util.Iterator<String> it = item.keys();
                    while (it.hasNext()) {
                        if (keys.length() > 0) keys.append(",");
                        keys.append(it.next());
                    }
                }
                Log.d(TAG, "超时件卡片 item keys: " + keys);
            } catch (Throwable ignore) {}

            View card = LayoutInflater.from(ctx).inflate(R.layout.item_timeout_card, timeoutListContainer, false);

            ImageView ivImage = card.findViewById(R.id.iv_package_image);
            TextView tvBillCode = card.findViewById(R.id.tv_bill_code);
            TextView tvRecipient = card.findViewById(R.id.tv_recipient);
            TextView tvArrivedAt = card.findViewById(R.id.tv_arrived_at);
            TextView tvCourier = card.findViewById(R.id.tv_courier);
            Button btnOutbound = card.findViewById(R.id.btn_outbound);

            // 超时件图片点击放大预览：优先使用全量列表翻页；enrich后若拿到新URL，则作为兜底单独预览
            final java.util.concurrent.atomic.AtomicReference<String> previewUrlRef =
                    new java.util.concurrent.atomic.AtomicReference<>("");

            // 提前提取 billCode（后续 enrich 和图片预览都需要）
            final String billCode = firstNonEmpty(
                    item.optString("billCode", ""),
                    item.optString("trackingNumber", ""),
                    item.optString("waybillCode", ""),
                    nestedString(item, "bill", "billCode"),
                    nestedString(item, "bill", "trackingNumber"));

            if (ivImage != null) {
                ivImage.setClickable(true);
                ivImage.setFocusable(true);
                ivImage.setOnClickListener(v -> {
                    try {
                        okhttp3.OkHttpClient cl = apiService != null ? apiService.getOkHttpClient() : null;
                        String url = previewUrlRef.get();
                        // 超时件界面不显示翻页按钮，始终单张预览，传入单号用于磁盘缓存兜底
                        ImagePreviewDialog.show(getContext(), url, billCode, cl);
                    }
                    catch (Throwable ignore) {}
                });
            }

            // 尝试从 item 表层，以及常见嵌套子对象中提取更多字段
            String receiveMan = firstNonEmpty(
                    item.optString("receiveMan", ""),
                    item.optString("recipientName", ""),
                    item.optString("receiver", ""),
                    nestedString(item, "bill", "receiveMan"),
                    nestedString(item, "bill", "recipientName"),
                    nestedString(item, "bill", "receiver"),
                    nestedString(item, "pkg", "receiveMan"),
                    nestedString(item, "pkg", "recipientName"),
                    nestedString(item, "packageInfo", "receiveMan"),
                    nestedString(item, "packageInfo", "recipientName"),
                    nestedString(item, "package", "receiveMan"),
                    nestedString(item, "package", "recipientName"),
                    nestedString(item, "info", "receiveMan"),
                    nestedString(item, "data", "receiveMan"));
            String receiveManMobile = firstNonEmpty(
                    item.optString("receiveManMobile", ""),
                    item.optString("receiveManPhone", ""),
                    item.optString("receiverMobile", ""),
                    item.optString("receiverPhone", ""),
                    item.optString("phone", ""),
                    item.optString("mobile", ""),
                    item.optString("telephone", ""),
                    nestedString(item, "bill", "receiveManMobile"),
                    nestedString(item, "bill", "phone"),
                    nestedString(item, "bill", "mobile"),
                    nestedString(item, "bill", "receiverMobile"),
                    nestedString(item, "pkg", "phone"),
                    nestedString(item, "pkg", "receiveManMobile"),
                    nestedString(item, "packageInfo", "phone"),
                    nestedString(item, "packageInfo", "receiveManMobile"),
                    nestedString(item, "package", "phone"),
                    nestedString(item, "info", "phone"),
                    nestedString(item, "data", "phone"));
            String arrivedAt = firstNonEmpty(
                    item.optString("arrivedAt", ""),
                    item.optString("time", ""),
                    item.optString("createTime", ""),
                    item.optString("timeoutTime", ""),
                    item.optString("registerDate", ""),
                    item.optString("stockInDate", ""),
                    nestedString(item, "bill", "arrivedAt"),
                    nestedString(item, "bill", "time"),
                    nestedString(item, "bill", "createTime"),
                    nestedString(item, "bill", "registerDate"),
                    nestedString(item, "pkg", "arrivedAt"),
                    nestedString(item, "pkg", "createTime"),
                    nestedString(item, "packageInfo", "arrivedAt"),
                    nestedString(item, "packageInfo", "createTime"),
                    nestedString(item, "package", "arrivedAt"),
                    nestedString(item, "package", "createTime"),
                    nestedString(item, "info", "arrivedAt"),
                    nestedString(item, "data", "arrivedAt"));
            // 快递公司（超时件接口可能返回 expressCompanyCode / courierCode，或需要 enrich 后填充）
            String rawCourier = firstNonEmpty(
                    item.optString("courier", ""),
                    item.optString("express", ""),
                    item.optString("expressCompanyName", ""),
                    item.optString("expressCompanyCode", ""),
                    item.optString("courierCode", ""),
                    nestedString(item, "bill", "expressCompanyName"),
                    nestedString(item, "bill", "courier"),
                    nestedString(item, "pkg", "expressCompanyName"),
                    nestedString(item, "packageInfo", "expressCompanyName"));
            String courierDisplay = mapCourierName(rawCourier);

            String imageUrl = firstNonEmpty(
                    item.optString("imageUrl", ""),
                    item.optString("imgUrl", ""),
                    item.optString("picture", ""),
                    item.optString("pic", ""),
                    item.optString("photo", ""),
                    nestedString(item, "bill", "imageUrl"),
                    nestedString(item, "bill", "imgUrl"),
                    nestedString(item, "pkg", "imageUrl"),
                    nestedString(item, "pkg", "imgUrl"),
                    nestedString(item, "packageInfo", "imageUrl"),
                    nestedString(item, "package", "imageUrl"),
                    nestedString(item, "info", "imageUrl"),
                    nestedString(item, "data", "imageUrl"));

            Log.d(TAG, "超时件卡片提取: billCode=" + billCode + " imageUrl=" + imageUrl + " rawCourier=" + rawCourier + " courierDisplay=" + courierDisplay);

            if (tvBillCode != null) {
                tvBillCode.setText(billCode);
                tvBillCode.setTypeface(Typeface.MONOSPACE);
            }
            // 收件人：姓名 + 空格 + 手机号（与查件界面显示一致）
            StringBuilder recipientDisplay = new StringBuilder();
            if (receiveMan.length() > 0) recipientDisplay.append(receiveMan);
            if (receiveManMobile.length() > 0) {
                if (recipientDisplay.length() > 0) recipientDisplay.append("  ");
                recipientDisplay.append(receiveManMobile);
            }
            if (tvRecipient != null) tvRecipient.setText(recipientDisplay.length() > 0 ? recipientDisplay.toString() : "—");
            if (tvArrivedAt != null) tvArrivedAt.setText(QueryFragment.formatDisplayTime(arrivedAt));
            if (tvCourier != null) {
                // 优先展示已有的映射结果；若没有，enrich 后会再次更新
                tvCourier.setText(courierDisplay.length() > 0 ? courierDisplay : "查询中…");
            }

            // Load package image：优先直接 imageUrl；否则按单号走【与查件界面 type=billCode 完全相同】的取数链路
            // （服务器模式：/api/query 带 type=billCode；直连模式：DirectApiClient.queryPackages(type=billCode)）
            if (ivImage != null) {
                if (imageUrl.length() > 0 && apiService != null) {
                    try {
                        String finalUrl = apiService.resolveImageUrl(imageUrl);
                        previewUrlRef.set(finalUrl);
                        ImageLoader.with(apiService.getOkHttpClient()).load(finalUrl, billCode, ivImage, R.drawable.bg_image_placeholder);
                    } catch (Exception ignore) {
                        ivImage.setImageResource(R.drawable.bg_image_placeholder);
                    }
                    // 仍尝试 enrich：补充快递名称等字段；enrich 后如果拿到更准的图片 URL，覆盖 previewUrlRef
                    if (billCode.length() > 0) {
                        enrichPackageByBillCode(billCode, ivImage, tvRecipient, tvArrivedAt, tvCourier, receiveMan, arrivedAt, courierDisplay, previewUrlRef);
                    }
                } else if (billCode.length() > 0) {
                    // 无直接 imageUrl：按单号查，拿到完整包裹对象后加载图片和快递名称
                    ivImage.setImageResource(R.drawable.bg_image_placeholder);
                    enrichPackageByBillCode(billCode, ivImage, tvRecipient, tvArrivedAt, tvCourier, receiveMan, arrivedAt, courierDisplay, previewUrlRef);
                } else {
                    ivImage.setImageResource(R.drawable.bg_image_placeholder);
                    if (tvCourier != null && courierDisplay.length() == 0) tvCourier.setText("—");
                }
            }

            if (outboundDoneBillCodes.contains(billCode)) {
                card.setAlpha(0.45f);
                if (btnOutbound != null) {
                    btnOutbound.setEnabled(false);
                    btnOutbound.setText("已出库");
                }
            }

            if (btnOutbound != null) {
                final View finalCard = card;
                final String finalBillCode = billCode;
                final String finalReceiveMan = receiveMan;
                btnOutbound.setOnClickListener(v -> doTimeoutOutbound(finalBillCode, finalReceiveMan, finalCard, btnOutbound));
            }

            timeoutListContainer.addView(card);
        } catch (Exception e) {
            safeToast("创建卡片失败: " + e.getMessage());
        }
    }

    private void doTimeoutOutbound(String billCode, String receiveMan, View card, Button btnOutbound) {
        if (!isAdded() || card == null) return;
        Log.d(TAG, "执行出库: billCode=" + billCode + " receiveMan=" + receiveMan);
        try { LogRecorder.info(requireContext(), "Timeout", "执行出库", "billCode=" + billCode + " receiveMan=" + receiveMan); } catch (Exception ignore) {}

        if (btnOutbound != null) {
            btnOutbound.setEnabled(false);
            btnOutbound.setText("出库中...");
        }

        apiService.outboundPackage(billCode, receiveMan, new ApiService.ApiCallback() {
            @Override
            public void onSuccess(JSONObject response) {
                if (!isViewReady) return;

                outboundDoneBillCodes.add(billCode);

                if (card != null) card.setAlpha(0.45f);
                if (btnOutbound != null) {
                    btnOutbound.setEnabled(false);
                    try { btnOutbound.setText("已出库"); } catch (Exception ignore) {}
                }

                if (localAudioEnabled) {
                    AudioPlayerHelper.playSuccess(requireContext());
                }

                safeToast("出库成功: " + billCode);

                try {
                    int remaining = 0;
                    for (int i = 0; i < timeoutListContainer.getChildCount(); i++) {
                        View child = timeoutListContainer.getChildAt(i);
                        if (child != null && child.getAlpha() > 0.9f) remaining++;
                    }
                    if (tvTimeoutCount != null) tvTimeoutCount.setText(remaining + " 件未出库");
                    if (remaining == 0 && tvTimeoutEmpty != null) {
                        tvTimeoutEmpty.setVisibility(View.VISIBLE);
                    }
                } catch (Exception ignore) {}
            }

            @Override
            public void onError(String error) {
                if (!isViewReady) return;

                if (localAudioEnabled) {
                    AudioPlayerHelper.playServerError(requireContext());
                }

                if (btnOutbound != null) {
                    try {
                        btnOutbound.setEnabled(true);
                        btnOutbound.setText("立即出库");
                    } catch (Exception ignore) {}
                }
                safeToast("出库失败: " + error);
            }
        });
    }

    // ===== Utility =====

    private static String firstNonEmpty(String... arr) {
        if (arr == null) return "";
        for (String s : arr) {
            if (s != null && s.length() > 0 && !"null".equalsIgnoreCase(s)) return s;
        }
        return "";
    }

    /**
     * 从 JSONObject item 中按嵌套路径 objKey.fieldKey 取值，
     * 例如 item = { bill: { picture: '/a.jpg' } }，调用 nestedString(item,"bill","picture") 返回 '/a.jpg'
     * 找不到或不是 JSONObject 返回空串
     */
    private static String nestedString(JSONObject item, String objKey, String fieldKey) {
        if (item == null || objKey == null || fieldKey == null) return "";
        try {
            JSONObject sub = item.optJSONObject(objKey);
            if (sub == null) return "";
            String v = sub.optString(fieldKey, "");
            return v == null ? "" : v;
        } catch (Throwable ignore) {
            return "";
        }
    }

    /**
     * 快递名称映射：zto → 中通快递，yunda → 韵达快递
     * 兼容英文编码/拼音编码/中文已格式化的各种写法，统一返回中文可读名称；
     * 输入为空或未知编码返回空串（调用方决定显示 "—" 或 "查询中…"）
     */
    private static String mapCourierName(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.length() == 0 || "null".equalsIgnoreCase(s)) return "";
        String low = s.toLowerCase(Locale.ROOT);
        // 已为中文直接返回
        if (s.contains("中通") || s.contains("中通快递")) return "中通快递";
        if (s.contains("韵达") || s.contains("韵达快递")) return "韵达快递";
        if (s.contains("圆通") || s.contains("圆通速递")) return "圆通速递";
        if (s.contains("申通") || s.contains("申通快递")) return "申通快递";
        if (s.contains("顺丰") || s.contains("顺丰速运")) return "顺丰速运";
        if (s.contains("百世") || s.contains("汇通")) return "百世快递";
        if (s.contains("邮政") || s.contains("EMS") || low.contains("ems")) return "EMS/邮政";
        if (s.contains("极兔")) return "极兔速递";
        if (s.contains("京东") || low.contains("jd")) return "京东物流";
        // 常见英文/拼音编码映射
        if (low.equals("zto") || low.equals("zhongtong") || low.equals("zt")
                || low.contains("zto")) return "中通快递";
        if (low.equals("yunda") || low.equals("yund") || low.equals("yd")
                || low.contains("yunda")) return "韵达快递";
        if (low.equals("yto") || low.equals("yuantong") || low.equals("yt")
                || low.contains("yto")) return "圆通速递";
        if (low.equals("sto") || low.equals("shentong") || low.equals("st")
                || low.contains("sto")) return "申通快递";
        if (low.equals("sf") || low.equals("shunfeng") || low.equals("sufeng")
                || low.contains("sf-express") || low.contains("sfexpress")) return "顺丰速运";
        if (low.equals("best") || low.equals("baishi") || low.contains("best")) return "百世快递";
        if (low.equals("jt") || low.equals("jitu") || low.contains("jtexpress")) return "极兔速递";
        // 其余非空值原样返回（可能是已格式化的其他公司名）
        return s;
    }

    /**
     * 按单号调用【与查件界面选择“运单号”查询方式完全一致】的接口获取完整包裹信息，
     * 服务器模式：apiService.queryPackageRaw(type=billCode)，直连模式：directApiClient.queryPackages(billCode, "billCode")
     * 拿到返回后：1) 写入 sBillPackageCache 缓存（按单号）；2) 加载图片；3) 更新快递名称 / 收件人 / 入库时间等显示。
     * 使用 view tag 绑定 billCode 防止 List 滚动复用导致错填。
     *
     * @param previewUrlRef 预览URL可变容器；enrich完成后写入最终resolve过的图片URL，供卡片ImageView点击放大预览使用。
     */
    private void enrichPackageByBillCode(final String billCode,
                                         final ImageView iv,
                                         final TextView tvRecipient,
                                         final TextView tvArrivedAt,
                                         final TextView tvCourier,
                                         final String currentRecipient,
                                         final String currentArrivedAt,
                                         final String currentCourierDisplay,
                                         final java.util.concurrent.atomic.AtomicReference<String> previewUrlRef) {
        if (billCode == null || billCode.isEmpty()) return;

        // 1) View tag 绑定当前 billCode（防止视图复用时错填）
        if (iv != null) iv.setTag(R.id.image_loader_tag, billCode);
        if (tvCourier != null) tvCourier.setTag(R.id.image_loader_tag, billCode);
        if (tvRecipient != null) tvRecipient.setTag(R.id.image_loader_tag, billCode);
        if (tvArrivedAt != null) tvArrivedAt.setTag(R.id.image_loader_tag, billCode);

        // 2) 缓存命中：直接从缓存填充
        JSONObject cached = sBillPackageCache.get(billCode);
        if (cached != null) {
            applyCachedPackageToViews(billCode, cached, iv, tvRecipient, tvArrivedAt, tvCourier,
                    currentRecipient, currentArrivedAt, currentCourierDisplay, previewUrlRef);
            return;
        }

        // 3) 缓存未命中：异步查询。服务器模式走 /api/query，直连模式走 DirectApiClient
        try {
            if (serverConnectEnabled && apiService != null) {
                JSONObject body = new JSONObject();
                body.put("billCode", billCode);
                body.put("type", "billCode");
                body.put("showDelivered", true);
                apiService.queryPackageRaw(body, new ApiService.ApiCallback() {
                    @Override
                    public void onSuccess(JSONObject response) {
                        if (!isViewReady) return;
                        JSONObject pkg0 = extractFirstPackageFromQueryResponse(response);
                        handleEnrichResult(billCode, pkg0, iv, tvRecipient, tvArrivedAt, tvCourier,
                                currentRecipient, currentArrivedAt, currentCourierDisplay, previewUrlRef);
                    }

                    @Override
                    public void onError(String error) {
                        Log.w(TAG, "enrich(server)按单号查询失败 billCode=" + billCode + " err=" + error);
                        markCourierFallback(tvCourier, billCode, currentCourierDisplay);
                    }
                });
            } else if (directApiClient != null) {
                new Thread(() -> {
                    JSONObject pkg0 = null;
                    try {
                        JSONObject resp = directApiClient.queryPackages(billCode, "billCode");
                        pkg0 = extractFirstPackageFromQueryResponse(resp);
                    } catch (Throwable t) {
                        Log.w(TAG, "enrich(direct)按单号查询失败 billCode=" + billCode + " err=" + t.getMessage());
                    }
                    final JSONObject finalPkg = pkg0;
                    if (!isViewReady) return;
                    try {
                        requireActivity().runOnUiThread(() -> handleEnrichResult(billCode, finalPkg, iv, tvRecipient, tvArrivedAt, tvCourier,
                                currentRecipient, currentArrivedAt, currentCourierDisplay, previewUrlRef));
                    } catch (Throwable ignore) {}
                }).start();
            } else {
                markCourierFallback(tvCourier, billCode, currentCourierDisplay);
            }
        } catch (Throwable t) {
            Log.w(TAG, "enrichPackageByBillCode 异常: " + t.getMessage());
            markCourierFallback(tvCourier, billCode, currentCourierDisplay);
        }
    }

    /** 从按单号查询的响应对象中提取第一个包裹对象（兼容 data[] / packages[] / result.stockInfos[] 等多种格式） */
    private JSONObject extractFirstPackageFromQueryResponse(JSONObject response) {
        if (response == null) return null;
        try {
            JSONArray arr = null;
            // 优先查 data
            if (response.has("data") && !response.isNull("data")) {
                Object d = response.get("data");
                if (d instanceof JSONArray) arr = (JSONArray) d;
                else if (d instanceof JSONObject) {
                    arr = pickArrayFromObject((JSONObject) d, "stockInfos", "packages", "items", "data");
                }
            }
            // 再查 packages
            if (arr == null || arr.length() == 0) {
                if (response.has("packages") && !response.isNull("packages")) {
                    Object p = response.get("packages");
                    if (p instanceof JSONArray) arr = (JSONArray) p;
                }
            }
            // 再查 result.stockInfos
            if (arr == null || arr.length() == 0) {
                if (response.has("result") && !response.isNull("result")) {
                    Object r = response.get("result");
                    if (r instanceof JSONObject) {
                        arr = pickArrayFromObject((JSONObject) r, "stockInfos", "items", "packages");
                    }
                }
            }
            if (arr != null && arr.length() > 0) return arr.optJSONObject(0);
        } catch (Throwable ignore) {}
        return null;
    }

    /** 从 JSONObject 中按候选 key 顺序找第一个 JSONArray */
    private static JSONArray pickArrayFromObject(JSONObject obj, String... keys) {
        if (obj == null || keys == null) return null;
        for (String k : keys) {
            try {
                if (obj.has(k) && !obj.isNull(k)) {
                    Object v = obj.get(k);
                    if (v instanceof JSONArray) return (JSONArray) v;
                }
            } catch (Throwable ignore) {}
        }
        return null;
    }

    /** enrich 查询完成后统一处理：缓存 + 应用到视图；tag 仍匹配当前 billCode 才更新 UI */
    private void handleEnrichResult(String billCode, JSONObject pkg,
                                    ImageView iv, TextView tvRecipient, TextView tvArrivedAt, TextView tvCourier,
                                    String currentRecipient, String currentArrivedAt, String currentCourierDisplay,
                                    java.util.concurrent.atomic.AtomicReference<String> previewUrlRef) {
        if (pkg != null) {
            sBillPackageCache.put(billCode, pkg);
            applyCachedPackageToViews(billCode, pkg, iv, tvRecipient, tvArrivedAt, tvCourier,
                    currentRecipient, currentArrivedAt, currentCourierDisplay, previewUrlRef);
        } else {
            Log.d(TAG, "按单号查询未命中包裹 billCode=" + billCode);
            markCourierFallback(tvCourier, billCode, currentCourierDisplay);
        }
    }

    /** 应用缓存的包裹对象到 UI（仅在 view tag 匹配时才写，防止列表复用时旧单号覆盖新单号） */
    private void applyCachedPackageToViews(String billCode, JSONObject pkg,
                                           ImageView iv, TextView tvRecipient, TextView tvArrivedAt, TextView tvCourier,
                                           String currentRecipient, String currentArrivedAt, String currentCourierDisplay,
                                           java.util.concurrent.atomic.AtomicReference<String> previewUrlRef) {
        if (pkg == null) return;
        // 提取字段（与 QueryFragment / DirectApiClient.queryPackages 保持同字段集合）
        String img = firstNonEmpty(
                pkg.optString("imageUrl", ""),
                pkg.optString("imgUrl", ""),
                pkg.optString("picture", ""),
                pkg.optString("pic", ""),
                pkg.optString("photo", ""),
                nestedString(pkg, "bill", "imageUrl"),
                nestedString(pkg, "bill", "imgUrl"),
                nestedString(pkg, "bill", "picture"),
                nestedString(pkg, "pkg", "imageUrl"),
                nestedString(pkg, "pkg", "imgUrl"),
                nestedString(pkg, "packageInfo", "imageUrl"));
        String recipient = firstNonEmpty(
                pkg.optString("recipientName", ""),
                pkg.optString("receiveMan", ""),
                pkg.optString("receiver", ""));
        String receiverMobile = firstNonEmpty(
                pkg.optString("receiveManMobile", ""),
                pkg.optString("receiveManPhone", ""),
                pkg.optString("receiverMobile", ""),
                pkg.optString("receiverPhone", ""),
                pkg.optString("phone", ""),
                pkg.optString("mobile", ""),
                pkg.optString("telephone", ""),
                nestedString(pkg, "bill", "receiveManMobile"),
                nestedString(pkg, "bill", "phone"),
                nestedString(pkg, "bill", "mobile"),
                nestedString(pkg, "pkg", "phone"),
                nestedString(pkg, "packageInfo", "phone"));
        String arrived = firstNonEmpty(
                pkg.optString("arrivedAt", ""),
                pkg.optString("time", ""),
                pkg.optString("createTime", ""),
                pkg.optString("billCodeScanTime", ""));
        String rawCourier = firstNonEmpty(
                pkg.optString("courier", ""),
                pkg.optString("express", ""),
                pkg.optString("expressCompanyName", ""),
                pkg.optString("expressCompanyCode", ""),
                pkg.optString("courierCode", ""));
        String courierName = mapCourierName(rawCourier);

        // 收件人显示：姓名 + 空格 + 手机号（与查件界面显示一致）；enrich 拿到的优先用，缺失时保留原卡片显示
        StringBuilder enrichRecipientDisplay = new StringBuilder();
        if (recipient.length() > 0) enrichRecipientDisplay.append(recipient);
        if (receiverMobile.length() > 0) {
            if (enrichRecipientDisplay.length() > 0) enrichRecipientDisplay.append("  ");
            enrichRecipientDisplay.append(receiverMobile);
        }

        Log.d(TAG, "按单号 enrich 结果: billCode=" + billCode + " img=" + img
                + " recipient=" + recipient + " mobile=" + receiverMobile
                + " courier=" + rawCourier + "→" + courierName);

        // 更新预览URL（即使 img 为空也要写，避免保持旧错误值）
        if (previewUrlRef != null && img.length() > 0 && apiService != null) {
            try { previewUrlRef.set(apiService.resolveImageUrl(img)); } catch (Throwable ignore) {}
        }

        // 只有 view tag 仍绑定当前 billCode 才更新 UI，防止列表滚动复用错填
        if (iv != null && billCode.equals(iv.getTag(R.id.image_loader_tag))) {
            if (img.length() > 0 && apiService != null) {
                try {
                    String finalUrl = apiService.resolveImageUrl(img);
                    ImageLoader.with(apiService.getOkHttpClient()).load(finalUrl, billCode, iv, R.drawable.bg_image_placeholder);
                } catch (Throwable ignore) {}
            }
        }
        if (tvRecipient != null && billCode.equals(tvRecipient.getTag(R.id.image_loader_tag))) {
            if (enrichRecipientDisplay.length() > 0) {
                tvRecipient.setText(enrichRecipientDisplay.toString());
            } else if (currentRecipient != null && currentRecipient.length() > 0) {
                tvRecipient.setText(currentRecipient); // 保持原有姓名+手机号的显示
            } else {
                tvRecipient.setText("—");
            }
        }
        if (tvArrivedAt != null && arrived.length() > 0
                && billCode.equals(tvArrivedAt.getTag(R.id.image_loader_tag))) {
            tvArrivedAt.setText(QueryFragment.formatDisplayTime(arrived));
        }
        if (tvCourier != null && billCode.equals(tvCourier.getTag(R.id.image_loader_tag))) {
            String toShow = courierName.length() > 0 ? courierName : currentCourierDisplay;
            tvCourier.setText(toShow.length() > 0 ? toShow : "—");
        }
    }

    /** enrich 查询失败时的兜底：若当前无任何展示值，显示 "—"（不覆盖已有合法展示） */
    private void markCourierFallback(TextView tvCourier, String billCode, String currentCourierDisplay) {
        if (tvCourier == null) return;
        try {
            if (billCode != null && !billCode.equals(tvCourier.getTag(R.id.image_loader_tag))) return;
            if (currentCourierDisplay != null && currentCourierDisplay.length() > 0) return;
            String existing = tvCourier.getText() == null ? "" : tvCourier.getText().toString();
            if (existing.length() == 0 || "查询中…".equals(existing)) {
                tvCourier.setText("—");
            }
        } catch (Throwable ignore) {}
    }
}
