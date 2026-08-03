package com.chajianzhushou.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognizerIntent;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.cardview.widget.CardView;
import androidx.fragment.app.Fragment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import okhttp3.OkHttpClient;

public class QueryFragment extends Fragment {
    private static final String TAG = "QueryFragment";
    private static final String PREFS_GRID = "query_grid_view";
    private static final String KEY_AUTO_REFRESH = "auto_refresh_interval";
    private static final int REQ_VOICE = 1001;

    // Views
    private ScrollView scrollView;
    private EditText etBillCode;
    private Button btnQuery;
    private Button btnClear;
    private Button btnTypePhone;
    private Button btnTypePickup;
    private Button btnTypeBill;
    private Button btnVoice;
    private SwitchCompat switchShowDelivered;
    private SwitchCompat switchGridView;
    private TextView tvResultCount;
    private LinearLayout resultsContainer;
    private ProgressBar progressBar;
    private LinearLayout tvNoResults;
    private FrameLayout loadingMask;

    // Core
    private ApiService apiService;
    private SyncClient syncClient;
    private DirectApiClient directApiClient;

    // State
    private String searchType = "phoneTail";
    private boolean isGridView = false;
    private boolean showDelivered = true;
    private boolean isAutoRefresh = false;
    private int lastPendingCount = -1;
    private volatile boolean isViewReady = false;
    private volatile long lastQueryAt = 0;
    private volatile boolean isQuerying = false;
    private List<JSONObject> currentPackages = new ArrayList<>();
    // 查询耗时埋点（避免并发，只在查询开始/回调/渲染三处读/写）
    private long __tReqStart = 0;
    private long __tRespArrived = 0;
    private String __queryMode = "";

    // 懒加载：分批渲染，首屏只渲染 BATCH_SIZE 条，滚动到底部再加载下一批
    private static final int BATCH_SIZE = 15;
    private int renderedCount = 0;
    private boolean isLoadingMore = false;
    private Runnable currentScrollListener = null;
    private long lastLoadMoreAt = 0; // 防抖：限制 loadMoreItems 调用频率
    // 用户是否正在触摸滑动（避免自动刷新在滑动时触发造成跳动）
    private volatile boolean isUserTouching = false;

    // 图片预览：所有非空图片 URL + 对应单号（支持跨包裹上下张翻页）
    private final List<String> allImageUrls = new ArrayList<>();
    private final List<String> allTrackingNos = new ArrayList<>();

    // Server sync state
    private boolean serverConnectEnabled = false;
    private boolean syncQueryEnabled = true;

    // Auto-refresh
    private Handler autoRefreshHandler;
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable autoRefreshRunnable;
    /** 实际触发自动刷新前 1s 提前激活指示器（变绿转动、文字变亮）的回调 */
    private Runnable autoRefreshPreActivate;

    // 自动刷新顶部指示器：空闲时暗色静止，执行自动刷新时变绿转动，完成时变暗停止
    private View autoRefreshIndicator;
    private TextView autoRefreshLabel;
    private android.view.animation.RotateAnimation autoRefreshSpinAnim;

    // TTS
    private TtsHelper ttsHelper;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_query, container, false);

        // Find all views
        scrollView = view.findViewById(R.id.scroll_query);
        etBillCode = view.findViewById(R.id.et_bill_code);
        btnQuery = view.findViewById(R.id.btn_query);
        btnClear = view.findViewById(R.id.btn_clear);
        btnTypePhone = view.findViewById(R.id.btn_type_phone);
        btnTypePickup = view.findViewById(R.id.btn_type_pickup);
        btnTypeBill = view.findViewById(R.id.btn_type_bill);
        btnVoice = view.findViewById(R.id.btn_voice);
        switchShowDelivered = view.findViewById(R.id.switch_show_delivered);
        switchGridView = view.findViewById(R.id.switch_grid_view);
        tvResultCount = view.findViewById(R.id.tv_result_count);
        resultsContainer = view.findViewById(R.id.results_container);
        // 网格行通过负 margin 向外贴边，行宽会超出本容器边界；
        // 关闭子视图裁剪，避免最左/最右卡片边缘（含边框、圆角）被裁掉
        resultsContainer.setClipChildren(false);
        resultsContainer.setClipToPadding(false);
        progressBar = view.findViewById(R.id.progress_bar);
        tvNoResults = view.findViewById(R.id.tv_no_results);
        loadingMask = view.findViewById(R.id.loading_mask);

        // 自动刷新指示器初始状态：暗色静止（圆环样式，空闲为静态暗环）
        autoRefreshIndicator = view.findViewById(R.id.auto_refresh_indicator);
        autoRefreshLabel = view.findViewById(R.id.auto_refresh_label);
        autoRefreshSpinAnim = new android.view.animation.RotateAnimation(0f, 360f,
                android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f,
                android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f);
        autoRefreshSpinAnim.setDuration(800);
        autoRefreshSpinAnim.setRepeatCount(android.view.animation.Animation.INFINITE);
        autoRefreshSpinAnim.setInterpolator(new android.view.animation.LinearInterpolator());
        setAutoRefreshIndicatorActive(false);

        apiService = new ApiService(requireContext());
        syncClient = new SyncClient(apiService);
        directApiClient = new DirectApiClient(requireContext());
        autoRefreshHandler = new Handler(Looper.getMainLooper());
        ttsHelper = TtsHelper.getInstance();
        ttsHelper.init(requireContext());
        isViewReady = true;

        // Load grid view preference - default to grid view ON (竖向排列=网格模式默认开启)
        SharedPreferences prefs = requireContext().getSharedPreferences("chajianzhushou_prefs", Context.MODE_PRIVATE);
        isGridView = prefs.getBoolean(PREFS_GRID, true);
        if (switchGridView != null) switchGridView.setChecked(isGridView);

        // Server sync state
        serverConnectEnabled = prefs.getBoolean("server_connect_enabled", false);
        syncQueryEnabled = prefs.getBoolean("sync_query_enabled", true);

        // Voice button state: disabled when ASR is off
        boolean asrEnabled = prefs.getBoolean("asr_enabled", true);
        updateVoiceButtonState(asrEnabled);

        // Sync callback — only connect SSE if server connection is enabled
        syncClient.setCallback(new SyncClient.SyncCallback() {
            @Override
            public void onQueryInputReceived(String value) {
                if (!isViewReady || etBillCode == null) return;
                if (value != null && !value.equals(etBillCode.getText().toString())) {
                    try {
                        etBillCode.setText(value);
                    } catch (Exception ignore) {}
                }
            }

            @Override
            public void onQueryTriggerReceived(String billCode, String type) {
                if (!isViewReady) return;
                if (billCode != null && etBillCode != null && !billCode.equals(etBillCode.getText().toString())) {
                    try {
                        etBillCode.setText(billCode);
                    } catch (Exception ignore) {}
                }
                if (type != null && type.length() > 0) setSearchType(type);
                performQuery(false);
            }

            @Override
            public void onConnected() {}

            @Override
            public void onDisconnected() {}

            @Override
            public void onError(String error) {}

            @Override
            public void onSettingsChanged(JSONObject settings) {
                if (!isViewReady) return;
                // 电脑端通过 /api/settings 广播 settings 事件（source=server），
                // 这里做"差分"：仅当值与当前不一致时才应用，避免与本地开关监听器互相回环
                mainHandler.post(() -> {
                    if (!isViewReady) return;
                    try {
                        if (settings.has("isGridView")) {
                            boolean gv = settings.optBoolean("isGridView", isGridView);
                            if (switchGridView != null && switchGridView.isChecked() != gv) {
                                switchGridView.setChecked(gv);
                            }
                        }
                        if (settings.has("showDelivered")) {
                            boolean sd = settings.optBoolean("showDelivered", showDelivered);
                            if (switchShowDelivered != null && switchShowDelivered.isChecked() != sd) {
                                switchShowDelivered.setChecked(sd);
                            }
                        }
                    } catch (Exception ignore) {}
                });
            }

            @Override
            public void onGridViewChanged(boolean gridView) {
                if (!isViewReady || switchGridView == null) return;
                mainHandler.post(() -> {
                    if (switchGridView != null && switchGridView.isChecked() != gridView) {
                        switchGridView.setChecked(gridView);
                    }
                });
            }

            @Override
            public void onShowDeliveredChanged(boolean showDelivered) {
                if (!isViewReady || switchShowDelivered == null) return;
                mainHandler.post(() -> {
                    if (switchShowDelivered != null && switchShowDelivered.isChecked() != showDelivered) {
                        switchShowDelivered.setChecked(showDelivered);
                    }
                });
            }
        });
        if (serverConnectEnabled) {
            syncClient.connect();
        }

        // Search type buttons
        btnTypePhone.setOnClickListener(v -> setSearchType("phoneTail"));
        btnTypePickup.setOnClickListener(v -> setSearchType("pickupCode"));
        btnTypeBill.setOnClickListener(v -> setSearchType("billCode"));
        updateTypeButtons();

        // Clear button
        btnClear.setOnClickListener(v -> {
            if (etBillCode != null) {
                etBillCode.setText("");
                etBillCode.requestFocus();
            }
        });

        // Input sync to PC
        etBillCode.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                if (serverConnectEnabled && syncQueryEnabled && syncClient != null) {
                    syncClient.sendInputSync(s == null ? "" : s.toString());
                }
            }
        });

        // Query button
        btnQuery.setOnClickListener(v -> performQuery(true));

        // Show delivered switch
        if (switchShowDelivered != null) {
            switchShowDelivered.setOnCheckedChangeListener((buttonView, isChecked) -> {
                showDelivered = isChecked;
                // 待取件卡片绿色边框标识随开关即时生效（无需等待重新查询）
                refreshAllCardPendingBorders();
                // 注意：切换开关时不无条件停止自动刷新
                // 只在查询完成后确认 pendingCount=0 时才停止（在 handleQueryResponse 中处理）
                fetchPackages();
                // Sync to PC
                if (serverConnectEnabled && syncQueryEnabled && syncClient != null) {
                    Log.d(TAG, "同步显示已出库开关: " + isChecked);
                    LogRecorder.info(requireContext(), "Query", "同步显示已出库开关", String.valueOf(isChecked));
                    syncClient.sendShowDeliveredSync(isChecked);
                }
            });
        }

        // Grid view switch
        if (switchGridView != null) {
            switchGridView.setOnCheckedChangeListener((buttonView, isChecked) -> {
                isGridView = isChecked;
                prefs.edit().putBoolean(PREFS_GRID, isChecked).apply();
                renderList();
                // Sync to PC
                if (serverConnectEnabled && syncQueryEnabled && syncClient != null) {
                    Log.d(TAG, "同步竖向排列开关: " + isChecked);
                    LogRecorder.info(requireContext(), "Query", "同步竖向排列开关", String.valueOf(isChecked));
                    syncClient.sendGridViewSync(isChecked);
                }
            });
        }

        // Voice button
        if (btnVoice != null) {
            btnVoice.setOnClickListener(v -> {
                if (!prefs.getBoolean("asr_enabled", true)) {
                    safeToast("语音识别未开启，请在设置中开启");
                    return;
                }
                startVoiceRecognition();
            });
        }

        // 用户触摸滑动检测：避免自动刷新在滑动时触发造成跳动
        if (scrollView != null) {
            scrollView.setOnTouchListener((v, event) -> {
                int action = event.getAction();
                if (action == android.view.MotionEvent.ACTION_DOWN) {
                    isUserTouching = true;
                } else if (action == android.view.MotionEvent.ACTION_UP
                        || action == android.view.MotionEvent.ACTION_CANCEL) {
                    // 松手后延迟重置，等待惯性滚动结束
                    mainHandler.postDelayed(() -> isUserTouching = false, 600);
                }
                return false; // 不消费事件，让 ScrollView 正常处理
            });
        }

        return view;
    }

    @Override
    public void onStart() {
        super.onStart();
        // 界面变为可见时，同步一次设置状态（防止从设置页返回时 onResume 因 Fragment 复用/缓存未触发）
        refreshSettingsFromPrefs();
    }

    @Override
    public void onResume() {
        super.onResume();
        // Refresh voice button state and sync state (may have changed in settings)
        refreshSettingsFromPrefs();
        // 非自动刷新状态下兜底隐藏"自动刷新中"文字（防止任何残留暗显文字）
        if (!isAutoRefresh) setAutoRefreshIndicatorActive(false);
    }

    /** 从 SharedPreferences 刷新所有可能被设置页修改的开关状态 */
    private void refreshSettingsFromPrefs() {
        if (!isViewReady) return;
        try {
            SharedPreferences prefs = requireContext().getSharedPreferences("chajianzhushou_prefs", Context.MODE_PRIVATE);
            updateVoiceButtonState(prefs.getBoolean("asr_enabled", true));
            boolean wasConnected = serverConnectEnabled;
            serverConnectEnabled = prefs.getBoolean("server_connect_enabled", false);
            syncQueryEnabled = prefs.getBoolean("sync_query_enabled", true);
            if (serverConnectEnabled && !wasConnected) {
                syncClient.connect();
            } else if (!serverConnectEnabled) {
                syncClient.disconnect();
            }
        } catch (Exception ignore) {}
    }

    /**
     * 屏幕旋转/尺寸变化时重新计算网格列数并重绘。
     * 因为 AndroidManifest 中 configChanges 阻止了 Activity 重建，必须在此手动响应。
     */
    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (!isViewReady || resultsContainer == null) return;
        // 只在网格模式下且已有查询结果时才需要重绘
        if (isGridView && currentPackages != null && currentPackages.size() > 0) {
            mainHandler.post(() -> {
                // 重新计算跨度，全量重绘（renderList 会重置 renderedCount 并分批渲染首屏）
                renderList();
            });
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_VOICE && resultCode == -1 && data != null) {
            ArrayList<String> results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (results != null && results.size() > 0) {
                String spokenText = results.get(0);
                if (etBillCode != null) {
                    etBillCode.setText(spokenText);
                }
                // Auto-detect search type based on digit count
                String digits = spokenText.replaceAll("[^0-9]", "");
                if (digits.length() == 11) {
                    setSearchType("phoneTail");
                } else if (digits.length() == 4) {
                    setSearchType("pickupCode");
                } else {
                    setSearchType("billCode");
                }
                performQuery(true);
            }
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        try {
            // 屏幕旋转 / Activity 重建时保留核心状态，避免查询结果丢失
            if (etBillCode != null) {
                outState.putString("qb_etBillCode", etBillCode.getText().toString());
            }
            outState.putString("qb_searchType", searchType);
            outState.putBoolean("qb_showDelivered", showDelivered);
            outState.putBoolean("qb_isGridView", isGridView);
            if (switchShowDelivered != null) {
                outState.putBoolean("qb_switchShowDelivered", switchShowDelivered.isChecked());
            }
            if (switchGridView != null) {
                outState.putBoolean("qb_switchGridView", switchGridView.isChecked());
            }
            if (currentPackages != null && currentPackages.size() > 0) {
                JSONArray arr = new JSONArray();
                for (JSONObject o : currentPackages) {
                    if (o != null) arr.put(o);
                }
                outState.putString("qb_currentPackagesJson", arr.toString());
                outState.putInt("qb_lastPendingCount", lastPendingCount);
            }
        } catch (Throwable ignore) {}
    }

    @Override
    public void onViewStateRestored(@Nullable Bundle savedInstanceState) {
        super.onViewStateRestored(savedInstanceState);
        restoreSavedState(savedInstanceState);
    }

    /** 从 savedInstanceState 还原查询结果、输入框内容等关键状态（旋转屏幕不会丢列表） */
    private void restoreSavedState(@Nullable Bundle savedInstanceState) {
        if (savedInstanceState == null) return;
        try {
            String savedBill = savedInstanceState.getString("qb_etBillCode", "");
            if (etBillCode != null && savedBill.length() > 0) {
                etBillCode.setText(savedBill);
            }
            String savedType = savedInstanceState.getString("qb_searchType", searchType);
            if (savedType != null && savedType.length() > 0) {
                searchType = savedType;
                updateTypeButtons();
            }
            if (savedInstanceState.containsKey("qb_showDelivered")) {
                showDelivered = savedInstanceState.getBoolean("qb_showDelivered", showDelivered);
            }
            if (switchShowDelivered != null && savedInstanceState.containsKey("qb_switchShowDelivered")) {
                boolean sd = savedInstanceState.getBoolean("qb_switchShowDelivered", showDelivered);
                switchShowDelivered.setChecked(sd);
                showDelivered = sd;
            }
            if (savedInstanceState.containsKey("qb_isGridView")) {
                isGridView = savedInstanceState.getBoolean("qb_isGridView", isGridView);
            }
            if (switchGridView != null && savedInstanceState.containsKey("qb_switchGridView")) {
                boolean gv = savedInstanceState.getBoolean("qb_switchGridView", isGridView);
                switchGridView.setChecked(gv);
                isGridView = gv;
            }
            if (savedInstanceState.containsKey("qb_lastPendingCount")) {
                lastPendingCount = savedInstanceState.getInt("qb_lastPendingCount", lastPendingCount);
            }
            String json = savedInstanceState.getString("qb_currentPackagesJson", "");
            if (json.length() > 0 && currentPackages != null) {
                JSONArray arr = new JSONArray(json);
                List<JSONObject> restored = new ArrayList<>(arr.length());
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o != null) restored.add(o);
                }
                if (restored.size() > 0) {
                    currentPackages = restored;
                    mainHandler.post(() -> {
                        try {
                            renderList();
                            Log.d(TAG, "恢复已保存的查询结果: " + restored.size() + " 条");
                            try { LogRecorder.info(requireContext(), "Query", "恢复已保存的查询结果", String.valueOf(restored.size())); } catch (Exception ignore) {}
                        } catch (Throwable ignore) {}
                    });
                }
            }
        } catch (Throwable ignore) {}
    }

    @Override
    public void onDestroyView() {
        isViewReady = false;
        isQuerying = false;

        detachScrollLoadMoreListener();
        scrollView = null;

        if (syncClient != null) {
            try { syncClient.disconnect(); } catch (Exception ignore) {}
            syncClient = null;
        }

        stopAutoRefresh();

        if (ttsHelper != null) {
            try { ttsHelper.stop(); } catch (Exception ignore) {}
        }

        etBillCode = null;
        btnQuery = null;
        btnClear = null;
        btnTypePhone = null;
        btnTypePickup = null;
        btnTypeBill = null;
        btnVoice = null;
        switchShowDelivered = null;
        switchGridView = null;
        tvResultCount = null;
        resultsContainer = null;
        progressBar = null;
        tvNoResults = null;
        loadingMask = null;
        apiService = null;
        autoRefreshHandler = null;
        autoRefreshRunnable = null;

        super.onDestroyView();
    }

    // ===== Helpers =====

    private void safeToast(String msg) {
        if (!isAdded() || !isViewReady) return;
        Context ctx = getContext();
        if (ctx == null) return;
        try { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show(); } catch (Exception ignore) {}
    }

    private void updateVoiceButtonState(boolean asrEnabled) {
        if (btnVoice == null) return;
        try {
            if (asrEnabled) {
                // ASR 开启：显示麦克风按钮，保持可点击
                btnVoice.setVisibility(View.VISIBLE);
                btnVoice.setEnabled(true);
                btnVoice.setClickable(true);
                btnVoice.setFocusable(true);
                btnVoice.setAlpha(1.0f);
                btnVoice.setTextColor(getResources().getColor(R.color.ink2, requireContext().getTheme()));
            } else {
                // ASR 关闭：完全隐藏麦克风按钮，腾空布局占用
                btnVoice.setVisibility(View.GONE);
                btnVoice.setEnabled(false);
                btnVoice.setClickable(false);
                btnVoice.setFocusable(false);
            }
        } catch (Exception ignore) {}
    }

    private void setSearchType(String type) {
        searchType = (type == null || type.isEmpty()) ? "phoneTail" : type;
        Log.d(TAG, "搜索类型切换: " + searchType);
        try { LogRecorder.info(requireContext(), "Query", "搜索类型切换", searchType); } catch (Exception ignore) {}
        // 切换搜索类型后停止当前的自动刷新循环
        stopAutoRefresh();
        updateTypeButtons();
    }

    private void updateTypeButtons() {
        if (btnTypePhone == null || btnTypePickup == null || btnTypeBill == null) return;
        try {
            btnTypePhone.setSelected("phoneTail".equals(searchType));
            btnTypePickup.setSelected("pickupCode".equals(searchType));
            btnTypeBill.setSelected("billCode".equals(searchType));
            btnTypePhone.setTextColor(getResources().getColor("phoneTail".equals(searchType) ? R.color.accent : R.color.ink2, requireContext().getTheme()));
            btnTypePickup.setTextColor(getResources().getColor("pickupCode".equals(searchType) ? R.color.accent : R.color.ink2, requireContext().getTheme()));
            btnTypeBill.setTextColor(getResources().getColor("billCode".equals(searchType) ? R.color.accent : R.color.ink2, requireContext().getTheme()));
        } catch (Throwable ignore) {}
    }

    // ===== Voice Recognition =====

    private void startVoiceRecognition() {
        Log.d(TAG, "启动语音识别");
        try { LogRecorder.info(requireContext(), "Query", "启动语音识别", ""); } catch (Exception ignore) {}
        try {
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN");
            intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "说出单号、手机号或取件码");
            startActivityForResult(intent, REQ_VOICE);
        } catch (Exception e) {
            safeToast("语音识别不可用: " + e.getMessage());
        }
    }

    // ===== Auto Refresh =====

    /** 自动刷新指示器：active=true 圆环变绿转动、"自动刷新中"文字变亮绿色；false 圆环变暗停止、文字变暗但保持显示。
     *  当"自动刷新间隔"设置为关闭(0)时，文字与圆环整体隐藏。自动刷新执行中禁止切换"竖向排列"开关。 */
    private void setAutoRefreshIndicatorActive(boolean active) {
        if (autoRefreshIndicator == null && autoRefreshLabel == null) return;
        try {
            // 自动刷新间隔关闭时：完全隐藏文字与圆环
            boolean enabled = getAutoRefreshSeconds() > 0;
            // 自动刷新执行中禁用"竖向排列"开关，避免网格/列表结构切换与自动刷新冲突
            if (switchGridView != null) switchGridView.setEnabled(enabled && !active);
            if (active) {
                autoRefreshIndicator.setBackgroundTintList(
                        android.content.res.ColorStateList.valueOf(0xFF00F5D4));
                autoRefreshIndicator.setAlpha(1f);
                autoRefreshIndicator.clearAnimation();
                autoRefreshIndicator.startAnimation(autoRefreshSpinAnim);
                if (autoRefreshLabel != null) {
                    autoRefreshLabel.setVisibility(enabled ? View.VISIBLE : View.GONE);
                    autoRefreshLabel.setTextColor(getResources().getColor(R.color.accent));
                    autoRefreshLabel.setAlpha(1f);
                }
                autoRefreshIndicator.setVisibility(enabled ? View.VISIBLE : View.GONE);
            } else {
                autoRefreshIndicator.clearAnimation();
                autoRefreshIndicator.setBackgroundTintList(
                        android.content.res.ColorStateList.valueOf(0xFF8A9099));
                autoRefreshIndicator.setAlpha(0.35f);
                if (autoRefreshLabel != null) {
                    // 文字保持显示但变灰、变暗、更透明（与圆环空闲态视觉一致）
                    autoRefreshLabel.setVisibility(enabled ? View.VISIBLE : View.GONE);
                    autoRefreshLabel.setTextColor(getResources().getColor(R.color.muted));
                    autoRefreshLabel.setAlpha(0.5f);
                }
                autoRefreshIndicator.setVisibility(enabled ? View.VISIBLE : View.GONE);
            }
        } catch (Exception ignore) {}
    }

    private int getAutoRefreshSeconds() {
        try {
            SharedPreferences prefs = requireContext().getSharedPreferences("chajianzhushou_prefs", Context.MODE_PRIVATE);
            return prefs.getInt(KEY_AUTO_REFRESH, 0);
        } catch (Exception e) {
            return 0;
        }
    }

    private void startAutoRefreshLoop() {
        if (autoRefreshHandler == null) return;
        stopAutoRefresh();
        int secs = getAutoRefreshSeconds();
        if (secs <= 0) return;
        long intervalMs = secs * 1000L;
        long preMs = Math.max(0, intervalMs - 1000L);
        // 实际触发自动刷新前 1s：提前让"自动刷新中"文字与圆环变绿转动，提示用户即将刷新
        autoRefreshPreActivate = () -> {
            if (!isViewReady || autoRefreshHandler == null) return;
            setAutoRefreshIndicatorActive(true);
        };
        autoRefreshHandler.postDelayed(autoRefreshPreActivate, preMs);
        autoRefreshRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isViewReady || autoRefreshHandler == null) return;
                // 用户正在触摸滑动时跳过本次自动刷新，延迟 2 秒后重试
                if (isUserTouching) {
                    autoRefreshHandler.postDelayed(this, 2000);
                    return;
                }
                if (etBillCode != null && etBillCode.getText().toString().trim().length() > 0) {
                    performQuery(false, true);
                }
            }
        };
        autoRefreshHandler.postDelayed(autoRefreshRunnable, intervalMs);
    }

    private void stopAutoRefresh() {
        if (autoRefreshHandler != null) {
            if (autoRefreshRunnable != null) {
                try { autoRefreshHandler.removeCallbacks(autoRefreshRunnable); } catch (Exception ignore) {}
                autoRefreshRunnable = null;
            }
            if (autoRefreshPreActivate != null) {
                try { autoRefreshHandler.removeCallbacks(autoRefreshPreActivate); } catch (Exception ignore) {}
                autoRefreshPreActivate = null;
            }
        }
        // 停止自动刷新时兜底隐藏"自动刷新中"文字与旋转（确保非加载状态不残留暗显文字）
        setAutoRefreshIndicatorActive(false);
    }

    // ===== Query =====

    private void performQuery(boolean syncToPc) {
        performQuery(syncToPc, false);
    }

    private void performQuery(boolean syncToPc, boolean isAuto) {
        if (!isViewReady || etBillCode == null) return;
        long now = System.currentTimeMillis();
        if (isQuerying) return;
        if (now - lastQueryAt < 400) return;
        lastQueryAt = now;
        isQuerying = true;
        __tReqStart = now;
        __tRespArrived = 0;
        __queryMode = "";

        String billCode = etBillCode.getText().toString().trim();
        if (billCode.isEmpty()) {
            isQuerying = false;
            if (!isAuto) safeToast("请输入查询内容");
            return;
        }

        boolean sd = (switchShowDelivered != null) ? switchShowDelivered.isChecked() : showDelivered;

        Log.d(TAG, "执行查询: billCode=" + billCode + " type=" + searchType + " showDelivered=" + sd + " isAuto=" + isAuto);
        try {
            LogRecorder.info(requireContext(), "Query", "执行查询",
                    "billCode=" + billCode + " type=" + searchType + " showDelivered=" + sd + " isAuto=" + isAuto);
        } catch (Exception ignore) {}

        if (syncToPc && serverConnectEnabled && syncQueryEnabled && syncClient != null) {
            syncClient.sendQueryTrigger(billCode, searchType, sd);
        }

        isAutoRefresh = isAuto;
        // 自动刷新执行：顶部指示器变绿转动；手动查询不激活（保持暗色静止）
        setAutoRefreshIndicatorActive(isAuto);
        if (!isAuto) {
            showLoading(true);
        }

        JSONObject body = new JSONObject();
        try {
            body.put("billCode", billCode);
            body.put("type", searchType);
            body.put("showDelivered", sd);
            if (isAuto) body.put("pendingOnly", true);

            if (serverConnectEnabled) {
                // Server mode: proxy through PC
                __queryMode = "SERVER";
                apiService.queryPackageRaw(body, new ApiService.ApiCallback() {
                    @Override
                    public void onSuccess(JSONObject response) {
                        __tRespArrived = System.currentTimeMillis();
                        handleQueryResponse(response, sd, isAuto);
                    }

                    @Override
                    public void onError(String error) {
                        isQuerying = false;
                        if (!isViewReady) return;
                        showLoading(false);
                        setAutoRefreshIndicatorActive(false);
                        if (!isAuto) safeToast("查询失败: " + error);
                        // 查询失败时如果设置了自动刷新间隔且有输入，保持循环继续尝试
                        rescheduleAutoRefreshOnErrorOrEmpty();
                    }
                });
            } else {
                // Direct mode: call ZTO API directly
                __queryMode = "DIRECT";
                new Thread(() -> {
                    try {
                        JSONObject response = directApiClient.queryPackages(billCode, searchType, isAuto);
                        __tRespArrived = System.currentTimeMillis();
                        if (!isViewReady) return;
                        mainHandler.post(() -> handleQueryResponse(response, sd, isAuto));
                    } catch (Exception e) {
                        if (!isViewReady) return;
                        mainHandler.post(() -> {
                            isQuerying = false;
                            showLoading(false);
                            setAutoRefreshIndicatorActive(false);
                            if (!isAuto) safeToast("查询失败: " + e.getMessage());
                            // 查询失败时如果设置了自动刷新间隔且有输入，保持循环继续尝试
                            rescheduleAutoRefreshOnErrorOrEmpty();
                        });
                    }
                }).start();
            }
        } catch (Exception e) {
            isQuerying = false;
            showLoading(false);
            setAutoRefreshIndicatorActive(false);
            if (!isAuto) safeToast("查询失败: " + e.getMessage());
            // 查询失败时如果设置了自动刷新间隔且有输入，保持循环继续尝试
            rescheduleAutoRefreshOnErrorOrEmpty();
        }
    }

    private void rescheduleAutoRefreshOnErrorOrEmpty() {
        if (!isViewReady) return;
        int interval = getAutoRefreshSeconds();
        if (interval <= 0) return;
        if (etBillCode == null || etBillCode.getText().toString().trim().isEmpty()) {
            stopAutoRefresh();
            return;
        }
        // 继续调度下一次自动查询
        startAutoRefreshLoop();
    }

    private void handleQueryResponse(JSONObject response, boolean sd, boolean isAuto) {
        long _tHandleStart = System.currentTimeMillis();
        long _networkCost = (__tReqStart > 0 && __tRespArrived > 0) ? (__tRespArrived - __tReqStart) : -1;
        isQuerying = false;
        if (!isViewReady) return;
        showLoading(false);
        // 自动刷新完成：指示器变暗停止
        setAutoRefreshIndicatorActive(false);

        if (response == null) {
            Log.w(TAG, "查询失败: 响应为空");
            try { LogRecorder.warn(requireContext(), "Query", "查询失败", "响应为空"); } catch (Exception ignore) {}
            if (!isAuto) safeToast("查询失败: 响应为空");
            rescheduleAutoRefreshOnErrorOrEmpty();
            return;
        }

        boolean ok = response.optBoolean("ok", false);
        if (!ok) {
            Log.w(TAG, "查询失败: " + response.optString("error", "未知错误"));
            try { LogRecorder.warn(requireContext(), "Query", "查询失败", response.optString("error", "未知错误")); } catch (Exception ignore) {}
            if (!isAuto) safeToast("查询失败: " + response.optString("error", "未知错误"));
            rescheduleAutoRefreshOnErrorOrEmpty();
            return;
        }

        // Extract packages array
        long _tParseStart = System.currentTimeMillis();
        JSONArray data = null;
        try {
            if (response.has("data") && !response.isNull("data")) {
                Object d = response.get("data");
                if (d instanceof JSONArray) data = (JSONArray) d;
                else if (d instanceof JSONObject) {
                    data = new JSONArray();
                    data.put(d);
                }
            }
        } catch (Exception ignore) {}

        List<JSONObject> newPackages = new ArrayList<>();
        if (data != null) {
            for (int i = 0; i < data.length(); i++) {
                try {
                    JSONObject item = data.getJSONObject(i);
                    if (item != null) {
                        // Filter by showDelivered
                        if (!sd) {
                            String st = item.optString("status", "");
                            if ("delivered".equals(st)) continue;
                        }
                        newPackages.add(item);
                    }
                } catch (Exception ignore) {}
            }
        }
        // 自动刷新时，保留旧列表中已出库的包裹（pendingOnly 查询不会返回它们，直接替换会导致卡片消失、列表跳动）
        final List<JSONObject> oldPackages = currentPackages; // 保存旧列表用于合并
        currentPackages = newPackages;

        if (isAuto && oldPackages != null && oldPackages.size() > 0) {
            java.util.Set<String> newBillCodes = new java.util.HashSet<>();
            for (JSONObject pkg : newPackages) {
                String bc = firstNonEmpty(pkg.optString("billCode", ""),
                        pkg.optString("trackingNumber", ""),
                        pkg.optString("waybillCode", ""));
                if (bc.length() > 0) newBillCodes.add(bc);
            }
            // 从旧列表中补充已出库包裹（不在新待取件列表中）
            for (JSONObject oldPkg : oldPackages) {
                if (!"delivered".equals(oldPkg.optString("status", ""))) continue;
                String bc = firstNonEmpty(oldPkg.optString("billCode", ""),
                        oldPkg.optString("trackingNumber", ""),
                        oldPkg.optString("waybillCode", ""));
                if (bc.length() > 0 && !newBillCodes.contains(bc)) {
                    currentPackages.add(oldPkg);
                }
            }
        }
        long _parseFilterCost = System.currentTimeMillis() - _tParseStart;

        // TTS
        int pendingCount = 0;
        for (JSONObject pkg : currentPackages) {
            if ("pending".equals(pkg.optString("status", ""))) pendingCount++;
        }

        Log.d(TAG, "查询完成: " + currentPackages.size() + " 条结果, pending=" + pendingCount);
        try {
            LogRecorder.info(requireContext(), "Query", "查询完成",
                    "总数=" + currentPackages.size() + " pending=" + pendingCount);
        } catch (Exception ignore) {}

        if (pendingCount != lastPendingCount || !isAutoRefresh) {
            if (pendingCount > 0) {
                String ttsText = "共" + pendingCount + "个待取包裹";
                try {
                    ttsHelper.speak(requireContext(), ttsText, new TtsHelper.TtsCallback() {
                        @Override
                        public void onDone() {}

                        @Override
                        public void onError(String error) {}
                    });
                } catch (Exception ignore) {}
            }
            lastPendingCount = pendingCount;
        }

        // Auto-refresh: 根据设置的间隔自动刷新查询，直到没有待取件(pending)包裹为止
        int interval = getAutoRefreshSeconds();
        if (interval > 0) {
            if (pendingCount > 0) {
                startAutoRefreshLoop();
            } else {
                stopAutoRefresh();
            }
        } else {
            stopAutoRefresh();
        }

        long _tRenderStart = System.currentTimeMillis();
        renderList();
        long _renderCost = System.currentTimeMillis() - _tRenderStart;
        long _otherCost = (_tRenderStart - _tHandleStart) - _parseFilterCost;
        if (_otherCost < 0) _otherCost = 0;
        long _totalCost = System.currentTimeMillis() - __tReqStart;
        String mode = (__queryMode == null || __queryMode.length() == 0) ? "UNKNOWN" : __queryMode;
        Log.d(TAG, String.format("[查询-耗时-%s] 网络=%dms | 解析+过滤=%dms(%d条) | TTS/其他=%dms | 渲染=%dms | 点击→渲染完总=%dms",
                mode,
                _networkCost,
                _parseFilterCost, currentPackages != null ? currentPackages.size() : 0,
                _otherCost,
                _renderCost,
                _totalCost));
        try {
            LogRecorder.info(requireContext(), "Query-Perf", mode,
                    String.format("network=%d parse=%d other=%d render=%d total=%d count=%d",
                            _networkCost, _parseFilterCost, _otherCost, _renderCost, _totalCost,
                            currentPackages != null ? currentPackages.size() : 0));
        } catch (Exception ignore) {}
        isAutoRefresh = false;
    }

    private void fetchPackages() {
        if (etBillCode == null) return;
        String bc = etBillCode.getText().toString().trim();
        if (bc.isEmpty()) return;
        performQuery(false);
    }

    // ===== Loading =====

    private void showLoading(boolean show) {
        if (!isViewReady) return;
        try {
            if (loadingMask != null) {
                // 存在全屏遮罩时：只显示遮罩，隐藏底部 ProgressBar（避免“两个 loading”同时显示）
                loadingMask.setVisibility(show ? View.VISIBLE : View.GONE);
                if (progressBar != null) progressBar.setVisibility(View.GONE);
            } else {
                // 遮罩不可用时（布局未加载完整等）：回退使用底部 ProgressBar
                if (progressBar != null) progressBar.setVisibility(show ? View.VISIBLE : View.GONE);
            }
            if (btnQuery != null) btnQuery.setEnabled(!show);
        } catch (Exception ignore) {}
    }

    // ===== Render List =====

    private int calculateGridSpanCount() {
        try {
            Context ctx = getContext();
            if (ctx == null) return 2;
            android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            int pagePadPx = ctx.getResources().getDimensionPixelSize(R.dimen.pad_page_h);
            int cardMinW = ctx.getResources().getDimensionPixelSize(R.dimen.grid_card_min_width);
            int gap = ctx.getResources().getDimensionPixelSize(R.dimen.grid_gap);
            int availW = dm.widthPixels - pagePadPx * 2;
            // 尝试 1..4
            int best = 1;
            for (int n = 2; n <= 4; n++) {
                int per = (availW - gap * (n - 1)) / n;
                if (per >= cardMinW) best = n;
                else break;
            }
            return best;
        } catch (Exception e) {
            return 2;
        }
    }

    private void renderList() {
        if (!isViewReady || resultsContainer == null || tvNoResults == null || tvResultCount == null) return;

        try {
            tvNoResults.setVisibility(View.GONE);

            int count = currentPackages.size();
            tvResultCount.setText(count + " 个包裹");

            if (count == 0) {
                tvNoResults.setVisibility(View.VISIBLE);
                resultsContainer.removeAllViews();
                synchronized (allImageUrls) { allImageUrls.clear(); allTrackingNos.clear(); }
                return;
            }

            // 构建全量图片列表（跨包裹翻页用），过滤掉空图片
            synchronized (allImageUrls) {
                allImageUrls.clear();
                allTrackingNos.clear();
                for (JSONObject item : currentPackages) {
                    String tno = firstNonEmpty(
                            item.optString("billCode", ""),
                            item.optString("trackingNumber", ""),
                            item.optString("waybillCode", ""));
                    String iurl = firstNonEmpty(
                            item.optString("imageUrl", ""),
                            item.optString("imgUrl", ""),
                            item.optString("picture", ""),
                            item.optString("pic", ""),
                            item.optString("photo", ""));
                    if (iurl.length() == 0) continue;
                    String resolved = (apiService != null) ? apiService.resolveImageUrl(iurl) : iurl;
                    if (resolved == null || resolved.length() == 0) continue;
                    allImageUrls.add(resolved);
                    allTrackingNos.add(tno);
                }
            }

            Context ctx = getContext();
            final float density = ctx.getResources().getDisplayMetrics().density;
            final int dp12 = (int) (12 * density + 0.5f);

            if (isAutoRefresh) {
                // 差分更新：标记待删除的旧卡片（billCode 不在新数据中）
                List<String> newIds = new ArrayList<>();
                java.util.Map<String, JSONObject> itemMap = new java.util.HashMap<>();
                for (JSONObject item : currentPackages) {
                    String iid = firstNonEmpty(
                            item.optString("billCode", ""),
                            item.optString("trackingNumber", ""),
                            item.optString("waybillCode", ""));
                    newIds.add(iid);
                    if (iid.length() > 0) itemMap.put(iid, item);
                }

                // 重建前记录当前可视区域顶部第一个包裹的单号，用于重建后恢复滚动位置
                String anchorBillCode = null;
                if (scrollView != null && resultsContainer.getChildCount() > 0) {
                    int scrollY = scrollView.getScrollY();
                    for (int ci = 0; ci < resultsContainer.getChildCount(); ci++) {
                        View child = resultsContainer.getChildAt(ci);
                        int childBottom = child.getBottom();
                        // 找到第一个至少部分可见的行/卡片
                        if (childBottom > scrollY) {
                            if (child instanceof LinearLayout && ((LinearLayout) child).getOrientation() == LinearLayout.HORIZONTAL) {
                                // 网格行：取该行第一个卡片的单号
                                LinearLayout row = (LinearLayout) child;
                                if (row.getChildCount() > 0) {
                                    Object tag = row.getChildAt(0).getTag(R.id.btn_query);
                                    if (tag != null) anchorBillCode = tag.toString();
                                }
                            } else if (!isGridView) {
                                // 列表模式：直接取卡片单号
                                Object tag = child.getTag(R.id.btn_query);
                                if (tag != null) anchorBillCode = tag.toString();
                            }
                            break;
                        }
                    }
                }

                if (isGridView) {
                    // ===== 网格模式：行级差分更新 =====
                    // 不整体 removeAllViews，而是逐行复用：内容一致的行完全不动（零闪烁），
                    // 只重建有变化的行，并追加新增行 / 删除多余行。
                    int spanCount = calculateGridSpanCount();

                    // 收集旧行和行内卡片映射（billCode → View）
                    List<LinearLayout> oldRows = new ArrayList<>();
                    java.util.Map<String, View> cardMap = new java.util.HashMap<>();
                    for (int i = 0; i < resultsContainer.getChildCount(); i++) {
                        View child = resultsContainer.getChildAt(i);
                        if (child instanceof LinearLayout && ((LinearLayout) child).getOrientation() == LinearLayout.HORIZONTAL) {
                            LinearLayout row = (LinearLayout) child;
                            oldRows.add(row);
                            for (int j = 0; j < row.getChildCount(); j++) {
                                View card = row.getChildAt(j);
                                Object tag = card.getTag(R.id.btn_query);
                                if (tag != null && tag.toString().length() > 0) {
                                    cardMap.put(tag.toString(), card);
                                }
                            }
                        }
                    }

                    // 计算新行划分：每行 spanCount 个
                    List<List<String>> newRowIds = new ArrayList<>();
                    for (int i = 0; i < newIds.size(); i += spanCount) {
                        newRowIds.add(newIds.subList(i, Math.min(i + spanCount, newIds.size())));
                    }

                    final int newRowCount = newRowIds.size();
                    // 预判断哪些旧行保持不动（内容与期望顺序完全一致 → 零闪烁）；保持不动的行里的卡片不可被挪用，否则会破坏该行
                    boolean[] keepRows = new boolean[oldRows.size()];
                    for (int ri = 0; ri < oldRows.size(); ri++) {
                        LinearLayout oldRow = oldRows.get(ri);
                        boolean same = ri < newRowCount && oldRow.getChildCount() == newRowIds.get(ri).size();
                        if (same) {
                            List<String> rowIds = newRowIds.get(ri);
                            for (int j = 0; j < rowIds.size(); j++) {
                                View card = oldRow.getChildAt(j);
                                Object tag = card.getTag(R.id.btn_query);
                                if (tag == null || !rowIds.get(j).equals(tag.toString())) { same = false; break; }
                            }
                        }
                        keepRows[ri] = same;
                    }
                    for (int ri = 0; ri < newRowCount; ri++) {
                        List<String> rowIds = newRowIds.get(ri);
                        if (ri < oldRows.size() && keepRows[ri]) continue; // 内容一致，完全不动（零闪烁）
                        LinearLayout targetRow;
                        if (ri < oldRows.size()) {
                            targetRow = oldRows.get(ri);
                            targetRow.removeAllViews();
                        } else {
                            // 新行：追加到容器末尾
                            targetRow = new LinearLayout(ctx);
                            targetRow.setOrientation(LinearLayout.HORIZONTAL);
                            targetRow.setGravity(Gravity.TOP);
                            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                            rowLp.bottomMargin = dp12;
                            targetRow.setLayoutParams(rowLp);
                        }
                        for (int j = 0; j < rowIds.size(); j++) {
                            View card = cardMap.get(rowIds.get(j));
                            // 复用前确认卡片可用：所在旧行若"保持不动"则不能挪用（新建）；
                            // 否则先从旧父容器摘除，避免 addView 抛"指定的子视图已有父容器"异常
                            if (card != null) {
                                ViewParent curParent = card.getParent();
                                if (curParent instanceof LinearLayout) {
                                    int oi = oldRows.indexOf(curParent);
                                    if (oi >= 0 && keepRows[oi]) {
                                        card = null;
                                    } else {
                                        ((LinearLayout) curParent).removeView(card);
                                    }
                                }
                            }
                            if (card == null) {
                                JSONObject item = itemMap.get(rowIds.get(j));
                                card = (item != null) ? createPackageCardView(item, true, spanCount) : null;
                                if (card == null) continue;
                            }
                            LinearLayout.LayoutParams cellLp = new LinearLayout.LayoutParams(
                                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
                            if (j > 0) cellLp.leftMargin = dp12;
                            card.setLayoutParams(cellLp);
                            targetRow.addView(card);
                        }
                        if (ri >= oldRows.size()) resultsContainer.addView(targetRow);
                    }
                    // 删除多余的旧行（从末尾开始移除）
                    for (int ri = oldRows.size() - 1; ri >= newRowCount; ri--) {
                        resultsContainer.removeView(oldRows.get(ri));
                    }
                } else {
                    // ===== 列表模式：清空后重建 =====
                    // 先收集需要保留的卡片（清空后再加回），避免整批重建闪烁
                    List<View> survivingCards = new ArrayList<>();
                    for (int i = 0; i < resultsContainer.getChildCount(); i++) {
                        View child = resultsContainer.getChildAt(i);
                        Object tag = child.getTag(R.id.btn_query);
                        String cid = tag != null ? tag.toString() : "";
                        if (newIds.contains(cid)) {
                            survivingCards.add(child);
                        }
                    }
                    resultsContainer.removeAllViews();
                    // 保留的卡片直接加回，新增的追加
                    java.util.Set<String> keptIds = new java.util.HashSet<>();
                    for (View card : survivingCards) {
                        Object tag = card.getTag(R.id.btn_query);
                        if (tag != null) keptIds.add(tag.toString());
                        resultsContainer.addView(card);
                    }
                    for (JSONObject item : currentPackages) {
                        String itemId = firstNonEmpty(
                                item.optString("billCode", ""),
                                item.optString("trackingNumber", ""),
                                item.optString("waybillCode", ""));
                        if (!keptIds.contains(itemId)) {
                            addPackageCard(item, false);
                        }
                    }
                }

                // 图片热更新：待取件包裹换新照片后 URL 变化 → 更新保留卡片的 ImageView 并重新加载
                // （ImageLoader 缓存按 URL 校验，URL 变化时旧图自动作废；同时同步刷新已打开的预览大图）
                refreshAllCardImages(itemMap);

                // 恢复滚动位置：找到锚点单号所在行，滚动到该行顶部
                final String anchor = anchorBillCode;
                if (anchor != null && scrollView != null && resultsContainer.getChildCount() > 0) {
                    scrollView.post(() -> {
                        int targetY = -1;
                        for (int ci = 0; ci < resultsContainer.getChildCount(); ci++) {
                            View child = resultsContainer.getChildAt(ci);
                            String rowAnchor = null;
                            if (child instanceof LinearLayout && ((LinearLayout) child).getOrientation() == LinearLayout.HORIZONTAL) {
                                if (((LinearLayout) child).getChildCount() > 0) {
                                    Object tag = ((LinearLayout) child).getChildAt(0).getTag(R.id.btn_query);
                                    if (tag != null) rowAnchor = tag.toString();
                                }
                            } else {
                                Object tag = child.getTag(R.id.btn_query);
                                if (tag != null) rowAnchor = tag.toString();
                            }
                            // 找到包含锚单号的行（或锚单号之后最近的）
                            if (rowAnchor != null && rowAnchor.equals(anchor)) {
                                targetY = child.getTop();
                                break;
                            }
                        }
                        if (targetY < 0) targetY = 0;
                        int maxY = Math.max(0, resultsContainer.getHeight() - scrollView.getHeight());
                        scrollView.scrollTo(0, Math.min(targetY, maxY));
                    });
                }

                renderedCount = currentPackages.size();
                detachScrollLoadMoreListener();
            } else {
                // Full rebuild with lazy loading: 首屏只渲染 BATCH_SIZE 条，滚动到底部再加载下一批
                resultsContainer.removeAllViews();
                renderedCount = 0;
                isLoadingMore = false;
                detachScrollLoadMoreListener();

                if (isGridView) {
                    int spanCount = calculateGridSpanCount();
                    final int N = currentPackages.size();
                    // 首屏批次数，向上取整到完整行
                    int firstBatch = Math.min(BATCH_SIZE, N);
                    firstBatch = ((firstBatch + spanCount - 1) / spanCount) * spanCount;
                    firstBatch = Math.min(firstBatch, N);

                    int idx = 0;
                    while (idx < firstBatch) {
                        int rowSize = Math.min(spanCount, firstBatch - idx);
                        LinearLayout row = new LinearLayout(ctx);
                        row.setOrientation(LinearLayout.HORIZONTAL);
                        row.setGravity(Gravity.TOP);
                        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                        rowLp.bottomMargin = dp12;
                        row.setLayoutParams(rowLp);
                        for (int c = 0; c < rowSize; c++) {
                            JSONObject item = currentPackages.get(idx + c);
                            CardView card = createPackageCardView(item, true, spanCount);
                            LinearLayout.LayoutParams cellLp = new LinearLayout.LayoutParams(
                                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
                            if (c > 0) cellLp.leftMargin = dp12;
                            card.setLayoutParams(cellLp);
                            row.addView(card);
                        }
                        resultsContainer.addView(row);
                        idx += rowSize;
                    }
                    renderedCount = idx;
                } else {
                    final int N = currentPackages.size();
                    int firstBatch = Math.min(BATCH_SIZE, N);
                    for (int i = 0; i < firstBatch; i++) {
                        addPackageCard(currentPackages.get(i), false);
                    }
                    renderedCount = firstBatch;
                }

                // 还有更多数据则注册滚动加载监听
                if (renderedCount < currentPackages.size()) {
                    attachScrollLoadMoreListener();
                }
            }
        } catch (Exception e) {
            // 渲染异常：不应误判为"无结果"，仅在确实没有任何包裹时才显示空状态
            Log.e(TAG, "渲染列表异常: " + e.getMessage(), e);
            try {
                if (currentPackages == null || currentPackages.isEmpty()) {
                    tvNoResults.setVisibility(View.VISIBLE);
                }
            } catch (Exception ignore) {}
        }
    }

    // ===== 自动刷新图片热更新 =====

    /** 遍历当前列表所有卡片，对换新照片的包裹更新 ImageView 并重新加载（含同步刷新已打开的预览大图）。 */
    private void refreshAllCardImages(java.util.Map<String, JSONObject> itemMap) {
        if (itemMap == null || itemMap.isEmpty() || resultsContainer == null) return;
        try {
            for (int i = 0; i < resultsContainer.getChildCount(); i++) {
                View child = resultsContainer.getChildAt(i);
                if (child instanceof LinearLayout && ((LinearLayout) child).getOrientation() == LinearLayout.HORIZONTAL) {
                    // 网格行：行内每个卡片
                    LinearLayout row = (LinearLayout) child;
                    for (int j = 0; j < row.getChildCount(); j++) {
                        refreshCardImage(row.getChildAt(j), itemMap);
                    }
                } else {
                    // 列表模式：直接是卡片
                    refreshCardImage(child, itemMap);
                }
            }
        } catch (Throwable ignore) {}
    }

    /** 单张卡片：新数据的图片 URL 与当前展示 URL 不一致时重新加载，并同步刷新正在预览该单号的预览大图。 */
    private void refreshCardImage(View card, java.util.Map<String, JSONObject> itemMap) {
        try {
            Object tag = card.getTag(R.id.btn_query);
            if (tag == null) return;
            JSONObject item = itemMap.get(tag.toString());
            if (item == null) return;
            String imageUrl = firstNonEmpty(
                    item.optString("imageUrl", ""),
                    item.optString("imgUrl", ""),
                    item.optString("picture", ""),
                    item.optString("pic", ""),
                    item.optString("photo", ""));
            if (imageUrl.length() == 0) return;
            String newUrl = (apiService != null) ? apiService.resolveImageUrl(imageUrl) : imageUrl;
            if (newUrl == null || newUrl.length() == 0) return;

            ImageView iv = findCardImageView(card);
            if (iv == null) return;
            Object curTag = iv.getTag(R.id.image_loader_tag);
            String curUrl = (curTag != null) ? curTag.toString() : "";
            if (newUrl.equals(curUrl)) return;

            // URL 变化 → 重新加载（内存/磁盘缓存按 URL 校验，旧图自动作废，换到新照片）
            String tno = tag.toString();
            ImageLoader.with(apiService.getOkHttpClient()).load(newUrl, tno, iv, R.drawable.bg_image_placeholder);

            // 若图片预览正打开且展示的正是该单号 → 同步切换大图
            try {
                ImagePreviewDialog dlg = ImagePreviewDialog.getActiveDialog();
                if (dlg != null) dlg.refreshImage(newUrl, tno);
            } catch (Throwable ignore) {}
        } catch (Throwable ignore) {}
    }

    /** 深度优先查找卡片内的包裹图片 ImageView（卡片结构：CardView → root LinearLayout → [ImageView, 信息区]）。 */
    private ImageView findCardImageView(View card) {
        if (card instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) card;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View c = vg.getChildAt(i);
                if (c instanceof ImageView) return (ImageView) c;
                if (c instanceof ViewGroup) {
                    ImageView sub = findCardImageView(c);
                    if (sub != null) return sub;
                }
            }
        }
        return null;
    }

    // ===== Lazy Load More =====

    private void attachScrollLoadMoreListener() {
        if (scrollView == null || !isViewReady) return;
        detachScrollLoadMoreListener();
        currentScrollListener = () -> {
            if (scrollView == null || resultsContainer == null || !isViewReady) return;
            if (isLoadingMore) return;
            if (renderedCount >= currentPackages.size()) {
                detachScrollLoadMoreListener();
                return;
            }
            // 检测是否即将滚动到底部（距离底部 < 300px 则加载更多）
            int scrollY = scrollView.getScrollY();
            int childH = resultsContainer.getHeight();
            int svH = scrollView.getHeight();
            int threshold = Math.max(svH / 3, 200);
            if (scrollY + svH >= childH - threshold) {
                loadMoreItems();
            }
        };
        scrollView.setOnScrollChangeListener((v, sx, sy, osx, osy) -> {
            if (currentScrollListener != null) currentScrollListener.run();
        });
    }

    private void detachScrollLoadMoreListener() {
        currentScrollListener = null;
        if (scrollView != null) {
            scrollView.setOnScrollChangeListener(null);
        }
    }

    private void loadMoreItems() {
        if (!isViewReady || resultsContainer == null || isLoadingMore) return;
        // 防抖：距离上次加载不到 300ms 则跳过，避免快速滑动时过度触发
        long now = System.currentTimeMillis();
        if (now - lastLoadMoreAt < 300) return;
        final int total = currentPackages != null ? currentPackages.size() : 0;
        if (renderedCount >= total) {
            detachScrollLoadMoreListener();
            return;
        }

        lastLoadMoreAt = now;
        isLoadingMore = true;
        Context ctx = getContext();
        if (ctx == null) { isLoadingMore = false; return; }
        final float density = ctx.getResources().getDisplayMetrics().density;
        final int dp12 = (int) (12 * density + 0.5f);

        if (isGridView) {
            int spanCount = calculateGridSpanCount();
            int nextBatch = Math.min(renderedCount + BATCH_SIZE, total);
            // 向上取整到完整行
            nextBatch = ((nextBatch + spanCount - 1) / spanCount) * spanCount;
            nextBatch = Math.min(nextBatch, total);
            int idx = renderedCount;
            while (idx < nextBatch) {
                int rowSize = Math.min(spanCount, nextBatch - idx);
                LinearLayout row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                // FILL：让同行所有子卡片自动撑开为相同高度（行高对齐）
                row.setGravity(Gravity.FILL_HORIZONTAL);
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rowLp.bottomMargin = dp12;
                row.setLayoutParams(rowLp);
                for (int c = 0; c < rowSize; c++) {
                    JSONObject item = currentPackages.get(idx + c);
                    CardView card = createPackageCardView(item, true, spanCount);
                    // 同行卡片高度统一：MATCH_PARENT 跟随行容器测量后的最高高度
                    LinearLayout.LayoutParams cellLp = new LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f);
                    if (c > 0) cellLp.leftMargin = dp12;
                    card.setLayoutParams(cellLp);
                    row.addView(card);
                }
                resultsContainer.addView(row);
                idx += rowSize;
            }
            renderedCount = idx;
        } else {
            int nextBatch = Math.min(renderedCount + BATCH_SIZE, total);
            for (int i = renderedCount; i < nextBatch; i++) {
                addPackageCard(currentPackages.get(i), false);
            }
            renderedCount = nextBatch;
        }

        if (renderedCount >= total) {
            detachScrollLoadMoreListener();
        }

        Log.d(TAG, "懒加载: 已渲染 " + renderedCount + "/" + total);
        try { LogRecorder.info(requireContext(), "Query-Lazy", "加载更多", renderedCount + "/" + total); } catch (Exception ignore) {}
        isLoadingMore = false;
    }

    // ===== Package Card =====

    /**
     * 待取件卡片使用绿色实线边框（2dp #00C853）作视觉区分，
     * 不受"显示已出库"开关影响；其他状态卡片使用默认背景。
     */
    private void applyCardPendingBorder(CardView card, boolean pending) {
        if (card == null) return;
        card.setBackgroundResource(pending
                ? R.drawable.bg_pkg_card_pending
                : R.drawable.bg_pkg_card);
    }

    private boolean isCardPending(View card) {
        Object t = card.getTag(R.id.tag_pkg_pending);
        return t instanceof Boolean && (Boolean) t;
    }

    /** 开关切换时立即刷新已渲染卡片的边框样式（无需等待重新查询） */
    private void refreshAllCardPendingBorders() {
        if (resultsContainer == null) return;
        for (int i = 0; i < resultsContainer.getChildCount(); i++) {
            View child = resultsContainer.getChildAt(i);
            if (child instanceof CardView) {
                applyCardPendingBorder((CardView) child, isCardPending(child));
            } else if (child instanceof LinearLayout && ((LinearLayout) child).getOrientation() == LinearLayout.HORIZONTAL) {
                LinearLayout row = (LinearLayout) child;
                for (int j = 0; j < row.getChildCount(); j++) {
                    View cell = row.getChildAt(j);
                    if (cell instanceof CardView) {
                        applyCardPendingBorder((CardView) cell, isCardPending(cell));
                    }
                }
            }
        }
    }

    /** Create a package card View only (for grid row embedding). Uses spanCount to tune image height. */
    private CardView createPackageCardView(JSONObject item, boolean vertical, int spanCount) {
        Context ctx = getContext();
        final android.content.res.Resources res = ctx.getResources();
        final int dp14 = res.getDimensionPixelSize(R.dimen.pad_card_h);
        final int dp12 = res.getDimensionPixelSize(R.dimen.spacing_lg);
        final int dp10 = res.getDimensionPixelSize(R.dimen.card_margin_bottom);
        final int dp8  = res.getDimensionPixelSize(R.dimen.spacing_md);
        final int dp6  = res.getDimensionPixelSize(R.dimen.spacing_sm);

        CardView card = new CardView(ctx);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp12;
        card.setLayoutParams(lp);
        card.setRadius(res.getDimension(R.dimen.radius_2xl));
        card.setCardElevation(0);
        card.setBackgroundResource(R.drawable.bg_pkg_card);

        String trackingNumber = firstNonEmpty(
                item.optString("billCode", ""),
                item.optString("trackingNumber", ""),
                item.optString("waybillCode", ""));
        String recipient = firstNonEmpty(
                item.optString("recipientName", ""),
                item.optString("receiveMan", ""),
                item.optString("receiver", ""));
        String pickupCode = firstNonEmpty(
                item.optString("pickupCode", ""),
                item.optString("takeCode", ""));
        String courier = firstNonEmpty(
                item.optString("courier", ""),
                item.optString("express", ""),
                item.optString("expressCompanyName", ""));
        String imageUrl = firstNonEmpty(
                item.optString("imageUrl", ""),
                item.optString("imgUrl", ""),
                item.optString("picture", ""),
                item.optString("pic", ""),
                item.optString("photo", ""));
        String arrivedAt = firstNonEmpty(
                item.optString("arrivedAt", ""),
                item.optString("time", ""),
                item.optString("createTime", ""));
        // 出库时间（仅 delivered / 已出库 状态下优先展示）
        String outboundTime = firstNonEmpty(
                item.optString("outboundTime", ""),
                item.optString("deliveryTime", ""),
                item.optString("deliveredTime", ""),
                item.optString("signedTime", ""),
                item.optString("signTime", ""),
                item.optString("outTime", ""),
                item.optString("outboundAt", ""),
                item.optString("outDate", ""),
                item.optString("pickupTime", ""));
        String status = item.optString("status", "pending");
        String mobile = firstNonEmpty(
                item.optString("receiveManMobile", ""),
                item.optString("phone", ""));

        int ink = ctx.getResources().getColor(R.color.ink, ctx.getTheme());
        int ink2 = ctx.getResources().getColor(R.color.ink2, ctx.getTheme());
        int muted = ctx.getResources().getColor(R.color.muted, ctx.getTheme());
        int accent = ctx.getResources().getColor(R.color.accent, ctx.getTheme());
        int danger = ctx.getResources().getColor(R.color.danger, ctx.getTheme());
        int champagne = ctx.getResources().getColor(R.color.champagne, ctx.getTheme());

        card.setTag(R.id.btn_query, trackingNumber);

        // Root container
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(vertical ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        root.setGravity(vertical ? Gravity.NO_GRAVITY : Gravity.CENTER_VERTICAL);
        root.setPadding(dp14, dp14, dp14, vertical ? dp10 : dp14);
        card.addView(root);

        // ===== Image =====
        ImageView iv = new ImageView(ctx);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackgroundResource(R.drawable.bg_pkg_image);
        // 图片内容按圆角背景裁切
        iv.setClipToOutline(true);
        if (vertical) {
            // 根据跨度调整图片高度：跨度越大高度越小（屏幕越宽卡片越小）
            int baseImgH = res.getDimensionPixelSize(R.dimen.grid_img_height);
            int imgH;
            if (spanCount >= 3)       imgH = baseImgH;
            else if (spanCount == 2)  imgH = (int) (baseImgH * 1.25f);
            else                       imgH = (int) (baseImgH * 1.6f);
            LinearLayout.LayoutParams imgLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, imgH);
            imgLp.bottomMargin = dp12;
            iv.setLayoutParams(imgLp);
        } else {
            int imgSize = res.getDimensionPixelSize(R.dimen.grid_img_height);
            LinearLayout.LayoutParams imgLp = new LinearLayout.LayoutParams(imgSize, imgSize);
            imgLp.rightMargin = dp14;
            imgLp.gravity = Gravity.TOP;
            iv.setLayoutParams(imgLp);
        }
        final String resolvedImageUrl;
        if (imageUrl.length() > 0 && apiService != null) {
            resolvedImageUrl = apiService.resolveImageUrl(imageUrl);
            ImageLoader.with(apiService.getOkHttpClient()).load(resolvedImageUrl, trackingNumber, iv, R.drawable.bg_image_placeholder);
        } else {
            resolvedImageUrl = "";
            iv.setImageResource(R.drawable.bg_image_placeholder);
        }
        // 点击图片放大预览（点击时读取 ImageView 当前 URL，自动刷新换新照片后预览到的也是最新图）
        iv.setClickable(true);
        iv.setFocusable(true);
        iv.setOnClickListener(v -> {
            try {
                OkHttpClient cl = apiService != null ? apiService.getOkHttpClient() : null;
                String curUrl = "";
                Object t = iv.getTag(R.id.image_loader_tag);
                if (t != null) curUrl = t.toString();
                int idx = -1;
                synchronized (allImageUrls) {
                    if (curUrl != null && curUrl.length() > 0) {
                        idx = allImageUrls.indexOf(curUrl);
                    }
                }
                if (idx >= 0) {
                    List<String> urlsCopy, nosCopy;
                    synchronized (allImageUrls) {
                        urlsCopy = new ArrayList<>(allImageUrls);
                        nosCopy = new ArrayList<>(allTrackingNos);
                    }
                    ImagePreviewDialog.show(getContext(), urlsCopy, idx, nosCopy, cl);
                } else if (curUrl != null && curUrl.length() > 0) {
                    ImagePreviewDialog.show(getContext(), curUrl, cl);
                }
            }
            catch (Throwable ignore) {}
        });
        root.addView(iv);

        // ===== Info section =====
        LinearLayout info = new LinearLayout(ctx);
        info.setOrientation(LinearLayout.VERTICAL);
        if (vertical) {
            // 网格模式：让 info 区自动撑开剩余空间，这样 statusTag 始终贴底，
            // 同行卡片即使内容高度不同，底部的状态 tag 也能保持水平对齐
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f);
            info.setLayoutParams(ilp);
        } else {
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            ilp.rightMargin = dp12;
            info.setLayoutParams(ilp);
        }

        addLabelValueRow(info, "单号", trackingNumber, ink2, champagne, ink, true, dp6, 12, 14, true);
        // 收件人(姓名+手机号)：网格窄卡下强制单行省略号，避免折行撑高卡片导致同行不齐
        StringBuilder who = new StringBuilder(recipient);
        if (mobile.length() > 0) who.append("  ").append(mobile);
        addLabelValueRow(info, "收件人", who.toString(), ink2, ink, ink, false, dp6, 12, 14, true);

        // 无论 pickUpCode 是否为空都固定显示一行，保证 info 区行数一致
        LinearLayout pickRow = makeLabelValueRow(ctx, "取件码", pickupCode,
                ink2, accent, ink, false, dp6, 12, 20, true);
        try {
            View val = pickRow.getChildAt(1);
            if (val instanceof TextView) {
                if (pickupCode.length() > 0) {
                    ((TextView) val).setTypeface(Typeface.MONOSPACE);
                    ((TextView) val).setTypeface(((TextView) val).getTypeface(), Typeface.BOLD);
                }
            }
        } catch (Throwable ignore) {}
        info.addView(pickRow);

        // 快递 + 时间：上下两行显示，避免网格模式/小屏时水平排列被截断
        LinearLayout metaWrap = new LinearLayout(ctx);
        metaWrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams mwLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mwLp.topMargin = dp8;
        metaWrap.setLayoutParams(mwLp);
        // 第一行：快递 chip（固定一行）
        metaWrap.addView(makeMetaChip(ctx, courier.length() > 0 ? courier : "—", muted, ink2, false));
        // 第二行：时间（按状态区分：入库时间 / 出库时间 + 前缀 + 值，允许折行）
        boolean isDelivered = "delivered".equals(status);
        String timeLabel = isDelivered ? "出库时间：" : "入库时间：";
        String rawTime = isDelivered ? (outboundTime.length() > 0 ? outboundTime : arrivedAt) : arrivedAt;
        String timeDisplay = timeLabel + formatDisplayTime(rawTime);
        TextView timeChip = new TextView(ctx);
        timeChip.setText(timeDisplay);
        timeChip.setTextColor(ink2);
        timeChip.setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.chip_text_size));
        timeChip.setBackgroundResource(R.drawable.bg_btn_back);
        int chipPad = res.getDimensionPixelSize(R.dimen.chip_padding_h);
        int chipPadV = res.getDimensionPixelSize(R.dimen.chip_padding_v);
        timeChip.setPadding(chipPad, chipPadV, chipPad, chipPadV);
        // 网格/列表均强制单行：避免两行撑开卡片高度造成同行不对齐
        timeChip.setSingleLine(true);
        timeChip.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tLp.topMargin = dp6;
        timeChip.setLayoutParams(tLp);
        metaWrap.addView(timeChip);
        info.addView(metaWrap);

        // ===== Status tag =====
        TextView statusTag = new TextView(ctx);
        boolean pending = "pending".equals(status);
        // 记录待取状态 + 应用"显示已出库激活时"的绿色边框标识
        card.setTag(R.id.tag_pkg_pending, pending);
        applyCardPendingBorder(card, pending);
        statusTag.setText(pending ? "待取件" : ("delivered".equals(status) ? "已出库" : status));
        statusTag.setTextColor(pending ? accent : danger);
        statusTag.setBackgroundResource(pending ? R.drawable.bg_status_pending : R.drawable.bg_status_delivered);
        statusTag.setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.chip_text_size));
        statusTag.setTypeface(Typeface.DEFAULT_BOLD);
        statusTag.setPadding(res.getDimensionPixelSize(R.dimen.chip_padding_h), res.getDimensionPixelSize(R.dimen.chip_padding_v), res.getDimensionPixelSize(R.dimen.chip_padding_h), res.getDimensionPixelSize(R.dimen.chip_padding_v));

        if (vertical) {
            LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            stLp.topMargin = dp10;
            stLp.gravity = Gravity.CENTER_HORIZONTAL;
            statusTag.setLayoutParams(stLp);
            root.addView(info);
            root.addView(statusTag);
        } else {
            LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            stLp.gravity = Gravity.CENTER_VERTICAL;
            statusTag.setLayoutParams(stLp);
            root.addView(info);
            root.addView(statusTag);
        }

        return card;
    }

    /** Legacy adapter: add single card directly to container (for list mode or diff-add) */
    private void addPackageCard(JSONObject item, boolean vertical) {
        if (!isViewReady || resultsContainer == null) return;
        Context ctx = getContext();
        if (ctx == null) return;

        try {
            final android.content.res.Resources res = getResources();
            final float d = res.getDisplayMetrics().density;
            int dp6 = res.getDimensionPixelSize(R.dimen.spacing_sm), dp8 = res.getDimensionPixelSize(R.dimen.spacing_md), dp10 = res.getDimensionPixelSize(R.dimen.card_margin_bottom);
            int dp12 = res.getDimensionPixelSize(R.dimen.spacing_lg), dp14 = res.getDimensionPixelSize(R.dimen.pad_card_h);

            // Multi-field extraction
            String trackingNumber = firstNonEmpty(
                    item.optString("billCode", ""),
                    item.optString("trackingNumber", ""),
                    item.optString("waybillCode", ""));
            String recipient = firstNonEmpty(
                    item.optString("receiveMan", ""),
                    item.optString("recipientName", ""),
                    item.optString("receiver", ""));
            String pickupCode = firstNonEmpty(
                    item.optString("pickupCode", ""),
                    item.optString("takeCode", ""),
                    item.optString("code", ""));
            String courier = firstNonEmpty(
                    item.optString("express", ""),
                    item.optString("expressCompany", ""),
                    item.optString("courier", ""));
            String imageUrl = firstNonEmpty(
                    item.optString("imageUrl", ""),
                    item.optString("imgUrl", ""),
                    item.optString("picture", ""),
                    item.optString("pic", ""),
                    item.optString("photo", ""));
            String arrivedAt = firstNonEmpty(
                    item.optString("arrivedAt", ""),
                    item.optString("time", ""),
                    item.optString("createTime", ""));
            // 出库时间（delivered 时优先展示）
            String outboundTime = firstNonEmpty(
                    item.optString("outboundTime", ""),
                    item.optString("deliveryTime", ""),
                    item.optString("deliveredTime", ""),
                    item.optString("signedTime", ""),
                    item.optString("signTime", ""),
                    item.optString("outTime", ""),
                    item.optString("outboundAt", ""),
                    item.optString("outDate", ""),
                    item.optString("pickupTime", ""));
            String status = item.optString("status", "pending");
            String mobile = firstNonEmpty(
                    item.optString("receiveManMobile", ""),
                    item.optString("phone", ""));

            int ink = getResources().getColor(R.color.ink, ctx.getTheme());
            int ink2 = getResources().getColor(R.color.ink2, ctx.getTheme());
            int muted = getResources().getColor(R.color.muted, ctx.getTheme());
            int accent = getResources().getColor(R.color.accent, ctx.getTheme());
            int danger = getResources().getColor(R.color.danger, ctx.getTheme());
            int champagne = getResources().getColor(R.color.champagne, ctx.getTheme());

            // CardView outer
            CardView card = new CardView(ctx);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp12;
            card.setLayoutParams(lp);
            card.setRadius(res.getDimension(R.dimen.radius_2xl));
            card.setCardElevation(0);
            card.setBackgroundResource(R.drawable.bg_pkg_card);

            // Root container
            LinearLayout root = new LinearLayout(ctx);
            root.setOrientation(vertical ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
            root.setGravity(vertical ? Gravity.NO_GRAVITY : Gravity.CENTER_VERTICAL);
            root.setPadding(dp14, dp14, dp14, vertical ? dp10 : dp14);
            card.addView(root);

            // ===== Image =====
            ImageView iv = new ImageView(ctx);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackgroundResource(R.drawable.bg_pkg_image);
            // 图片内容按圆角背景裁切
            iv.setClipToOutline(true);
            if (vertical) {
                int imgW = ViewGroup.LayoutParams.MATCH_PARENT;
                int imgH = (int) (res.getDimensionPixelSize(R.dimen.grid_img_height) * 1.25f);
                LinearLayout.LayoutParams imgLp = new LinearLayout.LayoutParams(imgW, imgH);
                imgLp.bottomMargin = dp12;
                iv.setLayoutParams(imgLp);
            } else {
                int imgSize = res.getDimensionPixelSize(R.dimen.grid_img_height);
                LinearLayout.LayoutParams imgLp = new LinearLayout.LayoutParams(imgSize, imgSize);
                imgLp.rightMargin = dp14;
                imgLp.gravity = Gravity.TOP;
                iv.setLayoutParams(imgLp);
            }
            final String resolvedImageUrl;
            if (imageUrl.length() > 0 && apiService != null) {
                resolvedImageUrl = apiService.resolveImageUrl(imageUrl);
                ImageLoader.with(apiService.getOkHttpClient()).load(resolvedImageUrl, trackingNumber, iv, R.drawable.bg_image_placeholder);
            } else {
                resolvedImageUrl = "";
                iv.setImageResource(R.drawable.bg_image_placeholder);
            }
            // 点击图片放大预览：使用 allImageUrls/allTrackingNos 全量列表，支持跨包裹上下张翻页
            iv.setClickable(true);
            iv.setFocusable(true);
            final String finalResolvedUrl = resolvedImageUrl;
            iv.setOnClickListener(v -> {
                try {
                    OkHttpClient cl = apiService != null ? apiService.getOkHttpClient() : null;
                    int idx = -1;
                    synchronized (allImageUrls) {
                        if (finalResolvedUrl != null && finalResolvedUrl.length() > 0) {
                            idx = allImageUrls.indexOf(finalResolvedUrl);
                        }
                    }
                    if (idx >= 0) {
                        List<String> urlsCopy, nosCopy;
                        synchronized (allImageUrls) {
                            urlsCopy = new ArrayList<>(allImageUrls);
                            nosCopy = new ArrayList<>(allTrackingNos);
                        }
                        ImagePreviewDialog.show(getContext(), urlsCopy, idx, nosCopy, cl);
                    } else {
                        ImagePreviewDialog.show(getContext(), finalResolvedUrl, cl);
                    }
                }
                catch (Throwable ignore) {}
            });
            root.addView(iv);

            // ===== Info section =====
            LinearLayout info = new LinearLayout(ctx);
            info.setOrientation(LinearLayout.VERTICAL);
            if (vertical) {
                // 网格模式：info 区自动撑开剩余空间，statusTag 贴底（同行对齐）
                LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f);
                info.setLayoutParams(ilp);
            } else {
                LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                ilp.rightMargin = dp12;
                info.setLayoutParams(ilp);
            }

            // Bill code row
            addLabelValueRow(info, "单号", trackingNumber, ink2, champagne, ink, true, dp6, 12, 14, true);

            // Recipient row：网格下单行省略，避免折行导致同行卡片高度不齐
            StringBuilder who = new StringBuilder(recipient);
            if (mobile.length() > 0) who.append("  ").append(mobile);
            addLabelValueRow(info, "收件人", who.toString(), ink2, ink, ink, false, dp6, 12, 14, true);

            // Pickup code row（vertical 模式强制固定一行，保证信息区高度一致）
            LinearLayout pickRow = makeLabelValueRow(ctx, "取件码", pickupCode,
                    ink2, accent, ink, false, dp6, 12, 20, true);
            try {
                View val = pickRow.getChildAt(1);
                if (val instanceof TextView) {
                    if (pickupCode.length() > 0) {
                        ((TextView) val).setTypeface(Typeface.MONOSPACE);
                        ((TextView) val).setTypeface(((TextView) val).getTypeface(), Typeface.BOLD);
                    }
                }
            } catch (Throwable ignore) {}
            info.addView(pickRow);

            // Meta row: courier(上) + arrivedAt(下) 各自占一行，避免时间被水平排列截断
            LinearLayout metaWrap = new LinearLayout(ctx);
            metaWrap.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams mwLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            mwLp.topMargin = dp8;
            metaWrap.setLayoutParams(mwLp);
            metaWrap.addView(makeMetaChip(ctx, courier.length() > 0 ? courier : "—", muted, ink2, false));
            // 时间：delivered 状态下显示"出库时间"（优先取outboundTime，没取到回退arrivedAt），其他显示"入库时间"
            boolean delivered2 = "delivered".equals(status);
            String timeLabel2 = delivered2 ? "出库时间：" : "入库时间：";
            String rawTime2 = delivered2 ? (outboundTime.length() > 0 ? outboundTime : arrivedAt) : arrivedAt;
            String timeDisplay2 = timeLabel2 + formatDisplayTime(rawTime2);
            TextView timeChip = new TextView(ctx);
            timeChip.setText(timeDisplay2);
            timeChip.setTextColor(ink2);
            timeChip.setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.chip_text_size));
            timeChip.setBackgroundResource(R.drawable.bg_btn_back);
            int cp2 = res.getDimensionPixelSize(R.dimen.chip_padding_h), cv2 = res.getDimensionPixelSize(R.dimen.chip_padding_v);
            timeChip.setPadding(cp2, cv2, cp2, cv2);
            // 强制单行省略：避免两行撑开卡片高度，造成同行卡片不齐
            timeChip.setSingleLine(true);
            timeChip.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams tLp2 = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tLp2.topMargin = dp6;
            timeChip.setLayoutParams(tLp2);
            metaWrap.addView(timeChip);
            info.addView(metaWrap);

            // ===== Status tag =====
            TextView statusTag = new TextView(ctx);
            boolean pending = "pending".equals(status);
            // 记录待取状态 + 应用"显示已出库激活时"的绿色边框标识
            card.setTag(R.id.tag_pkg_pending, pending);
            applyCardPendingBorder(card, pending);
            statusTag.setText(pending ? "待取件" : ("delivered".equals(status) ? "已出库" : status));
            statusTag.setTextColor(pending ? accent : danger);
            statusTag.setBackgroundResource(pending ? R.drawable.bg_status_pending : R.drawable.bg_status_delivered);
            statusTag.setTextSize(12f);
            statusTag.setTypeface(Typeface.DEFAULT_BOLD);
            statusTag.setPadding((int) (12 * d + 0.5f), (int) (6 * d + 0.5f), (int) (12 * d + 0.5f), (int) (6 * d + 0.5f));

            if (vertical) {
                // In vertical mode, status tag goes below info, centered
                LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                stLp.topMargin = dp10;
                stLp.gravity = Gravity.CENTER_HORIZONTAL;
                statusTag.setLayoutParams(stLp);
                root.addView(info);
                root.addView(statusTag);
            } else {
                // In horizontal mode, status tag on the right side
                LinearLayout actions = new LinearLayout(ctx);
                actions.setOrientation(LinearLayout.VERTICAL);
                actions.setGravity(Gravity.CENTER_VERTICAL);
                LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
                actions.setLayoutParams(alp);
                actions.addView(statusTag);
                root.addView(info);
                root.addView(actions);
            }

            // Set tag for differential refresh
            card.setTag(R.id.btn_query, trackingNumber);
            resultsContainer.addView(card);
        } catch (Exception e) {
            safeToast("创建卡片失败: " + e.getMessage());
        }
    }

    // ===== Label/Value Row Helpers =====

    private static LinearLayout makeLabelValueRow(Context ctx, String label, String value,
                                                   int labelColor, int valueColor, int fallback,
                                                   boolean valueBold, int bottomMargin,
                                                   int labelSizeSp, int valueSizeSp,
                                                   boolean singleLine) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = bottomMargin;
        row.setLayoutParams(rlp);

        TextView lv = new TextView(ctx);
        lv.setText(label);
        lv.setTextColor(labelColor);
        lv.setTextSize(labelSizeSp);
        lv.setTypeface(Typeface.DEFAULT_BOLD);
        lv.setAllCaps(true);
        lv.setLetterSpacing(0.08f);
        lv.setMinWidth(ctx.getResources().getDimensionPixelSize(R.dimen.grid_label_min_width));
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        // 标签与值之间的间隔缩小（12dp→6dp），窄卡片下给值留出更多宽度，减少"显示不全"
        llp.rightMargin = ctx.getResources().getDimensionPixelSize(R.dimen.spacing_sm);
        lv.setLayoutParams(llp);
        row.addView(lv);

        TextView vv = new TextView(ctx);
        vv.setText(value == null || value.isEmpty() ? "\u2014" : value);
        vv.setTextColor(value == null || value.isEmpty() ? fallback : valueColor);
        vv.setTextSize(valueSizeSp);
        if (valueBold) vv.setTypeface(Typeface.DEFAULT_BOLD);
        if (singleLine) {
            vv.setSingleLine(true);
            vv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        } else {
            vv.setSingleLine(false);
        }
        row.addView(vv);
        return row;
    }

    private static void addLabelValueRow(LinearLayout parent, String label, String value,
                                          int labelColor, int valueColor, int fallback,
                                          boolean valueBold, int bottomMargin,
                                          int labelSizeSp, int valueSizeSp,
                                          boolean singleLine) {
        Context ctx = parent.getContext();
        parent.addView(makeLabelValueRow(ctx, label, value, labelColor, valueColor, fallback,
                valueBold, bottomMargin, labelSizeSp, valueSizeSp, singleLine));
    }

    private static TextView makeMetaChip(Context ctx, String text, int border, int textColor) {
        return makeMetaChip(ctx, text, border, textColor, false);
    }

    private static TextView makeMetaChip(Context ctx, String text, int border, int textColor, boolean singleLine) {
        TextView tv = new TextView(ctx);
        tv.setText(text == null ? "" : text);
        tv.setTextColor(textColor);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, ctx.getResources().getDimension(R.dimen.chip_text_size));
        final android.content.res.Resources res = ctx.getResources();
        int chipPadH = res.getDimensionPixelSize(R.dimen.chip_padding_h);
        int chipPadV = res.getDimensionPixelSize(R.dimen.chip_padding_v);
        tv.setBackgroundResource(R.drawable.bg_btn_back);
        tv.setPadding(chipPadH, chipPadV, chipPadH, chipPadV);
        if (singleLine) {
            tv.setSingleLine(true);
            tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tv.setMaxWidth(res.getDimensionPixelSize(R.dimen.chip_max_width));
        }
        return tv;
    }

    private static String firstNonEmpty(String... arr) {
        if (arr == null) return "";
        for (String s : arr) {
            if (s != null && s.length() > 0 && !"null".equalsIgnoreCase(s)) return s;
        }
        return "";
    }

    /** 将 ZTO API 返回的原始时间字符串/时间戳转为可读格式 "yyyy-MM-dd HH:mm"（仅日期时 "yyyy-MM-dd"）
     *  支持：ISO "2026-07-31 14:25:00" / "2026-07-31T14:25:00.000+08:00"、"2026/07/31 14:25"、
     *       紧凑 "20260731142500" / "20260731"、纯毫秒/秒时间戳 */
    public static String formatDisplayTime(String raw) {
        if (raw == null || raw.trim().length() == 0) return "—";
        try {
            String s = raw.trim();
            // 1) 紧凑格式：yyyyMMddHHmmss
            if (s.matches("\\d{14}")) {
                return s.substring(0, 4) + "-" + s.substring(4, 6) + "-" + s.substring(6, 8)
                        + " " + s.substring(8, 10) + ":" + s.substring(10, 12);
            }
            // 2) 紧凑格式：yyyyMMdd
            if (s.matches("\\d{8}")) {
                return s.substring(0, 4) + "-" + s.substring(4, 6) + "-" + s.substring(6, 8);
            }
            // 3) 纯数字时间戳（秒 / 毫秒）
            if (s.matches("\\d{10}")) {
                return formatTimestamp(Long.parseLong(s) * 1000L);
            }
            if (s.matches("\\d{13,}")) {
                return formatTimestamp(Long.parseLong(s));
            }
            // 4) 标准格式：统一 T→空格、/→-，去掉时区偏移
            String norm = s.replace('T', ' ').replace('/', '-');
            String[] dt = norm.split(" ");
            if (dt.length >= 1) {
                String[] dateParts = dt[0].split("-");
                if (dateParts.length == 3 && dateParts[0].length() == 4) {
                    String y = dateParts[0];
                    String m = dateParts[1].length() == 1 ? "0" + dateParts[1] : dateParts[1];
                    String d = dateParts[2].length() == 1 ? "0" + dateParts[2] : dateParts[2];
                    String out = y + "-" + m + "-" + d;
                    if (dt.length >= 2) {
                        String t = dt[1];
                        int plus = t.indexOf('+');
                        if (plus > 0) t = t.substring(0, plus);
                        int zIdx = t.indexOf('Z');
                        if (zIdx > 0) t = t.substring(0, zIdx);
                        String[] tp = t.split(":");
                        if (tp.length >= 2 && tp[0].length() >= 1 && tp[0].length() <= 2) {
                            String hh = tp[0].length() == 1 ? "0" + tp[0] : tp[0];
                            String mm = tp[1].length() == 1 ? "0" + tp[1] : tp[1].substring(0, Math.min(2, tp[1].length()));
                            if (hh.matches("\\d{2}") && mm.matches("\\d{2}")) {
                                out += " " + hh + ":" + mm;
                            }
                        }
                    }
                    return out;
                }
            }
            // 5) 仅时间 "14:25:00"
            if (s.matches("\\d{1,2}:\\d{2}.*")) {
                String[] tp = s.split(":");
                String hh = tp[0].length() == 1 ? "0" + tp[0] : tp[0];
                return hh + ":" + tp[1].substring(0, Math.min(2, tp[1].length()));
            }
        } catch (Exception ignore) {}
        return raw; // 解析失败保持原样
    }

    /** 毫秒时间戳 → "yyyy-MM-dd HH:mm" */
    private static String formatTimestamp(long millis) {
        try {
            java.util.Calendar cal = java.util.Calendar.getInstance();
            cal.setTimeInMillis(millis);
            return String.format(Locale.getDefault(), "%04d-%02d-%02d %02d:%02d",
                    cal.get(java.util.Calendar.YEAR),
                    cal.get(java.util.Calendar.MONTH) + 1,
                    cal.get(java.util.Calendar.DAY_OF_MONTH),
                    cal.get(java.util.Calendar.HOUR_OF_DAY),
                    cal.get(java.util.Calendar.MINUTE));
        } catch (Exception e) {
            return String.valueOf(millis);
        }
    }
}
