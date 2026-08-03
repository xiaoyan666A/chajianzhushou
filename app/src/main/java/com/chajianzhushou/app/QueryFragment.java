package com.chajianzhushou.app;

import android.content.Context;
import android.content.Intent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognizerIntent;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
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
    private static final String PREFS_HISTORY = "query_history";
    private static final int MAX_HISTORY = 20;
    private static final int HISTORY_PANEL_MAX_ROWS = 10;

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
    private LinearLayout historyPanel;
    private ProgressBar progressBar;
    private LinearLayout tvNoResults;
    private FrameLayout loadingMask;

    // Core
    private ApiService apiService;
    private SyncClient syncClient;
    private DirectApiClient directApiClient;

    // State
    private String searchType = "phoneTail";
    // 最后一次实际查询的值：清空输入框后，自动刷新仍按此值继续查询，列表不消失
    private String lastQueriedBillCode = "";
    private boolean isGridView = false;
    private boolean showDelivered = true;
    private boolean isAutoRefresh = false;
    private int lastPendingCount = -1;
    // 用户最近一次操作列表（按下/松手）的时间：用于避免快速滑动时底部加载/滚动恢复干扰
    private long lastUserScrollAt = 0;
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
    // 直连模式懒加载：单号+原始路径 → 已解析成功的图片 URL（供预览列表重建与重绘时复用）
    private final java.util.Map<String, String> resolvedImageUrls = new java.util.concurrent.ConcurrentHashMap<>();
    // 出入库照片对比：单号 → {另一张照片URL, 名称(入库照/出库照)}，供预览大图切换
    private final java.util.Map<String, String[]> comparePhotoMap = new java.util.concurrent.ConcurrentHashMap<>();
    // 超时件标注配置：remark=="超时出库" 且 出库时间在"最近N天"内的已出库包裹，叠加三层标注
    private static final String KEY_TIMEOUT_MARK_DAYS = "timeout_mark_days";
    private static final int DEFAULT_TIMEOUT_MARK_DAYS = 3;
    private static final String KEY_TIMEOUT_MARK_ENABLED = "timeout_mark_enabled";
    // 单号 → 卡片边框/标签闪烁任务（超时件专用，缓慢持续闪烁）
    private final java.util.Map<String, Runnable> timeoutBlinkMap = new java.util.HashMap<>();

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

    // 语音识别使用 Activity Result API（替代已弃用的 startActivityForResult/onActivityResult）
    private final androidx.activity.result.ActivityResultLauncher<Intent> voiceLauncher =
            registerForActivityResult(
                    new androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (!isViewReady) return;
                        if (result.getResultCode() == android.app.Activity.RESULT_OK && result.getData() != null) {
                            ArrayList<String> results = result.getData().getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
                            if (results != null && results.size() > 0) {
                                String spokenText = results.get(0);
                                // 语音指令解析：识别"查一下尾号2979 / 取件码1234 / 运单号xxx"等关键词与类型
                                String[] parsed = parseVoiceQuery(spokenText);
                                if (etBillCode != null) {
                                    etBillCode.setText(parsed[0]);
                                }
                                setSearchType(parsed[1]);
                                try {
                                    LogRecorder.info(requireContext(), "ASR", "语音查询",
                                            "value=" + parsed[0] + " type=" + parsed[1]);
                                } catch (Exception ignore) {}
                                performQuery(true, false, null, false); // 语音识别不计入查询历史
                            }
                        }
                    });

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
        historyPanel = view.findViewById(R.id.history_panel);
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
        btnQuery.setOnClickListener(v -> {
            hideHistoryPanel();
            performQuery(true);
        });

        // 输入框聚焦：展开最近查询记录；失焦：延迟收起（给点击记录留时间）
        etBillCode.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                renderHistory();
            } else {
                mainHandler.postDelayed(() -> {
                    if (historyPanel != null && !etBillCode.hasFocus()) {
                        historyPanel.setVisibility(View.GONE);
                    }
                }, 200);
            }
        });

        // 点击输入框（即使已保持焦点）也重新展开查询历史——修复"第二次点击不显示"：
        // 按返回键收起键盘时输入框不丢焦点，第二次点击不会触发 onFocusChange
        etBillCode.setOnTouchListener((v, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_UP) {
                renderHistory();
            }
            return false;
        });

        // 键盘隐藏时自动收起查询历史面板（输入框可能仍保持焦点）。
        // 用"显示→隐藏"状态跳变判断，避免键盘弹起动画期间（高度还很小）误把面板收起。
        final boolean[] keyboardVisible = {false};
        view.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            if (historyPanel == null) return;
            try {
                android.graphics.Rect r = new android.graphics.Rect();
                view.getWindowVisibleDisplayFrame(r);
                int screenH = view.getRootView().getHeight();
                int kbH = screenH - r.bottom;
                boolean nowVisible = kbH >= screenH / 4;
                if (keyboardVisible[0] && !nowVisible) {
                    historyPanel.setVisibility(View.GONE);
                } else if (!keyboardVisible[0] && nowVisible
                        && etBillCode != null && etBillCode.hasFocus()) {
                    // 键盘从隐藏→弹出且输入框有焦点：补一次显示
                    renderHistory();
                }
                keyboardVisible[0] = nowVisible;
            } catch (Throwable ignore) {}
        });

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
                    lastUserScrollAt = System.currentTimeMillis();
                } else if (action == android.view.MotionEvent.ACTION_UP
                        || action == android.view.MotionEvent.ACTION_CANCEL) {
                    lastUserScrollAt = System.currentTimeMillis();
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
            // 旋转后列数必须重算：等容器按新方向完成布局后再强制整表重建。
            // 若在布局前用旧宽度重建，列数会沿用旧方向的值，导致每次旋转列数错乱/递增；
            // 若走差分路径，旧行会按旧列数原样保留。
            resultsContainer.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(View v, int l, int t, int r, int b,
                                           int ol, int ot, int or, int ob) {
                    if (r - l == or - ol && b - t == ob - ot) return; // 尺寸未变，等待下一次
                    resultsContainer.removeOnLayoutChangeListener(this);
                    // 布局已完成：投递到主队列再重建，避免在布局过程中修改视图导致列表消失
                    resultsContainer.post(() -> {
                        if (!isViewReady || resultsContainer == null) return;
                        boolean wasAuto = isAutoRefresh;
                        isAutoRefresh = false;
                        renderList();
                        isAutoRefresh = wasAuto;
                    });
                }
            });
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
            outState.putString("qb_lastQueriedBillCode", lastQueriedBillCode);
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
            String savedLast = savedInstanceState.getString("qb_lastQueriedBillCode", "");
            if (savedLast != null && savedLast.length() > 0) {
                lastQueriedBillCode = savedLast;
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
        stopAllTimeoutBlink();

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
        historyPanel = null;
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
            voiceLauncher.launch(intent);
        } catch (Exception e) {
            safeToast("语音识别不可用: " + e.getMessage());
        }
    }

    /**
     * 语音指令解析：返回 {查询值, 搜索类型}。
     * 关键词优先：尾号/手机尾 → phoneTail；取件码/取货码 → pickupCode；运单/单号 → billCode。
     * 无关键词时按数字长度兜底：11位→手机尾号，4位→取件码，其他→运单号。
     */
    private String[] parseVoiceQuery(String spoken) {
        String s = (spoken == null) ? "" : spoken.trim();
        String digits = s.replaceAll("[^0-9]", "");
        String type;
        if (s.contains("尾号") || s.contains("手机尾") || s.toLowerCase().contains("电话尾")) {
            type = "phoneTail";
        } else if (s.contains("取件码") || s.contains("取货码") || s.contains("提货码")) {
            type = "pickupCode";
        } else if (s.contains("运单") || s.contains("快递单") || s.contains("单号") || s.contains("包裹号")) {
            type = "billCode";
        } else {
            if (digits.length() == 11) type = "phoneTail";
            else if (digits.length() == 4) type = "pickupCode";
            else type = "billCode";
        }
        String value = digits.length() > 0 ? digits : s;
        return new String[]{value, type};
    }

    // ===== Auto Refresh =====

    /** 自动刷新指示器：active=true 圆环变绿转动、"自动刷新中"文字变亮绿色；false 圆环变暗停止、文字变暗但保持显示。
     *  当"自动刷新间隔"设置为关闭(0)时，文字与圆环整体隐藏。自动刷新执行中禁止切换"竖向排列"开关。 */
    private void setAutoRefreshIndicatorActive(boolean active) {
        if (autoRefreshIndicator == null && autoRefreshLabel == null) return;
        try {
            // 自动刷新间隔关闭时：完全隐藏文字与圆环
            boolean enabled = getAutoRefreshSeconds() > 0;
            // 仅在自动刷新"执行中"禁用"竖向排列"开关（避免切换与刷新冲突）；
            // 自动刷新间隔关闭时开关保持可用，不受影响。
            if (switchGridView != null) switchGridView.setEnabled(!active);
            if (active) {
                autoRefreshIndicator.setBackgroundTintList(
                        android.content.res.ColorStateList.valueOf(0xFF00F5D4));
                autoRefreshIndicator.setAlpha(1f);
                autoRefreshIndicator.clearAnimation();
                autoRefreshIndicator.startAnimation(autoRefreshSpinAnim);
                if (autoRefreshLabel != null) {
                    autoRefreshLabel.setVisibility(enabled ? View.VISIBLE : View.GONE);
                    autoRefreshLabel.setTextColor(getResources().getColor(R.color.accent, requireContext().getTheme()));
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
                    autoRefreshLabel.setTextColor(getResources().getColor(R.color.muted, requireContext().getTheme()));
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
                if (etBillCode != null) {
                    String q = etBillCode.getText().toString().trim();
                    if (q.length() == 0) {
                        // 输入框被清空：保留列表，继续按"最后一次查询条件"自动刷新
                        q = lastQueriedBillCode;
                    }
                    if (q.length() > 0) {
                        performQuery(false, true, q);
                        return;
                    }
                }
                // 从未查询过且输入为空：没有可继续刷新的条件，停止并复位指示器
                stopAutoRefresh();
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
        performQuery(syncToPc, false, null, true);
    }

    private void performQuery(boolean syncToPc, boolean isAuto) {
        performQuery(syncToPc, isAuto, null, true);
    }

    private void performQuery(boolean syncToPc, boolean isAuto, String explicitValue) {
        performQuery(syncToPc, isAuto, explicitValue, true);
    }

    /**
     * @param explicitValue 显式查询值；为 null 时读取输入框内容。
     *                     输入框清空后自动刷新会传入最后一次查询值，保证列表不消失、刷新继续。
     * @param recordHistory 是否计入"最近查询"历史（语音识别、自动刷新不计入）
     */
    private void performQuery(boolean syncToPc, boolean isAuto, String explicitValue, boolean recordHistory) {
        if (!isViewReady || etBillCode == null) return;
        long now = System.currentTimeMillis();
        if (isQuerying) return;
        if (now - lastQueryAt < 400) return;
        lastQueryAt = now;
        isQuerying = true;
        __tReqStart = now;
        __tRespArrived = 0;
        __queryMode = "";

        String billCode = (explicitValue != null) ? explicitValue : etBillCode.getText().toString().trim();
        if (billCode.isEmpty()) {
            isQuerying = false;
            if (!isAuto) safeToast("请输入查询内容");
            return;
        }
        // 记住本次实际查询条件：清空输入后自动刷新仍按此继续
        lastQueriedBillCode = billCode;
        // 记录查询历史（输入框聚焦时展示"最近查询"）；自动刷新/语音识别不计入
        if (recordHistory && !isAuto) {
            recordQueryHistory(billCode, searchType);
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
        if (etBillCode == null) {
            stopAutoRefresh();
            return;
        }
        String q = etBillCode.getText().toString().trim();
        if (q.length() == 0) q = lastQueriedBillCode; // 输入清空后仍按最后一次查询条件继续
        if (q.length() == 0) {
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
                        // Filter by showDelivered：关闭"显示已出库"时仍保留超时件（remark=超时出库且在N天内）
                        if (!sd) {
                            String st = item.optString("status", "");
                            if ("delivered".equals(st) && !isTimeoutPackage(item)) continue;
                        }
                        newPackages.add(item);
                    }
                } catch (Exception ignore) {}
            }
        }
        // 自动刷新时，保留旧列表中已出库的包裹（pendingOnly 查询不会返回它们，直接替换会导致卡片消失、列表跳动）
        final List<JSONObject> oldPackages = currentPackages; // 保存旧列表用于合并
        currentPackages = newPackages;

        java.util.Set<String> autoFreshBillCodes = null; // pendingOnly 响应基线，供补查"消失的待取件"用
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
            // 显示已出库开启：旧"待取件"在响应中消失（多半刚出库）时，先保留原卡片避免闪烁消失，
            // 并记录 pendingOnly 响应基线，稍后按单号补查最新状态、原位更新为已出库。
            if (sd) {
                autoFreshBillCodes = newBillCodes;
                for (JSONObject oldPkg : oldPackages) {
                    if ("delivered".equals(oldPkg.optString("status", ""))) continue;
                    String bc = firstNonEmpty(oldPkg.optString("billCode", ""),
                            oldPkg.optString("trackingNumber", ""),
                            oldPkg.optString("waybillCode", ""));
                    if (bc.length() > 0 && !newBillCodes.contains(bc)) {
                        currentPackages.add(oldPkg);
                    }
                }
            }
        }
        // 自动刷新 + 显示已出库：旧列表中的"待取件"在 pendingOnly 响应中消失 → 多半刚出库。
        // 按单号补查最新状态并用新数据原位替换，保证卡片不消失，状态/边框/图片实时更新为已出库。
        if (isAuto && sd && autoFreshBillCodes != null) {
            refreshMissingPendingAfterAutoRefresh(oldPackages, autoFreshBillCodes);
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
                // 自动刷新后没有待取件了：自动清空输入框。
                // 仅自动刷新场景生效，且输入框内容仍是本次查询值时才清（避免打断用户正在输入的新内容）。
                if (isAuto && etBillCode != null) {
                    String cur = etBillCode.getText().toString().trim();
                    if (cur.length() > 0 && cur.equals(lastQueriedBillCode)) {
                        etBillCode.setText("");
                    }
                }
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
        if (bc.isEmpty()) {
            // 输入框已清空：按最后一次查询条件刷新，保证"显示已出库"开关切换等操作仍然生效
            bc = lastQueriedBillCode;
        }
        if (bc.isEmpty()) return;
        performQuery(false, false, bc);
    }

    /**
     * 自动刷新后，补查在 pendingOnly 响应（基线）中消失的旧"待取件"单号（可能刚出库）。
     * 最多同时补查 5 个，防止异常数据导致请求堆积。
     *
     * @param freshBillCodes 本次 pendingOnly 响应中出现的单号集合（未包含被保留的旧卡片）
     */
    private void refreshMissingPendingAfterAutoRefresh(List<JSONObject> oldPackages, java.util.Set<String> freshBillCodes) {
        if (oldPackages == null || oldPackages.isEmpty() || freshBillCodes == null || !isViewReady) return;
        try {
            int fetchCount = 0;
            for (JSONObject oldPkg : oldPackages) {
                if (fetchCount >= 5) break;
                if ("delivered".equals(oldPkg.optString("status", ""))) continue;
                String bc = firstNonEmpty(oldPkg.optString("billCode", ""),
                        oldPkg.optString("trackingNumber", ""),
                        oldPkg.optString("waybillCode", ""));
                if (bc.length() > 0 && !freshBillCodes.contains(bc)) {
                    fetchCount++;
                    fetchFreshPackageByBillCode(bc, oldPkg);
                }
            }
        } catch (Throwable ignore) {}
    }

    /** 按单号补查包裹最新状态（服务器模式走 /api/query，直连模式走 ZTO 接口），与"运单号"查询链路一致。 */
    private void fetchFreshPackageByBillCode(final String billCode, final JSONObject oldPkg) {
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
                        mainHandler.post(() -> applyFreshPackage(billCode, oldPkg,
                                extractFirstPackageFromQueryResponse(response)));
                    }
                    @Override
                    public void onError(String error) {}
                });
            } else if (directApiClient != null) {
                new Thread(() -> {
                    JSONObject fresh = null;
                    try {
                        fresh = extractFirstPackageFromQueryResponse(directApiClient.queryPackages(billCode, "billCode"));
                    } catch (Throwable ignore) {}
                    if (!isViewReady) return;
                    final JSONObject finalFresh = fresh;
                    try {
                        mainHandler.post(() -> applyFreshPackage(billCode, oldPkg, finalFresh));
                    } catch (Throwable ignore) {}
                }, "fresh-pkg").start();
            }
        } catch (Throwable ignore) {}
    }

    /** 从按单号查询响应中提取第一个包裹对象（兼容 data[] 数组或单个对象）。 */
    private JSONObject extractFirstPackageFromQueryResponse(JSONObject response) {
        if (response == null) return null;
        try {
            if (response.has("data") && !response.isNull("data")) {
                Object d = response.get("data");
                if (d instanceof JSONArray) {
                    JSONArray arr = (JSONArray) d;
                    if (arr.length() > 0) return arr.optJSONObject(0);
                } else if (d instanceof JSONObject) {
                    return (JSONObject) d;
                }
            }
        } catch (Throwable ignore) {}
        return null;
    }

    /** 用补查到的最新包裹数据替换列表中的旧条目（已出库则显示新状态/新图片），并触发差分重绘。 */
    private void applyFreshPackage(String billCode, JSONObject oldPkg, JSONObject fresh) {
        if (!isViewReady || fresh == null) return;
        try {
            if (fresh.length() == 0) return; // 查询不到：保持现状，不主动删除卡片
            Log.d(TAG, "补查单号=" + billCode + " 旧状态=" + oldPkg.optString("status", "")
                    + " 新状态=" + fresh.optString("status", "") + " 新图=" + fresh.optString("imageUrl", ""));
            try {
                LogRecorder.info(requireContext(), "Query", "出库状态补查",
                        "billCode=" + billCode + " old=" + oldPkg.optString("status", "")
                                + " new=" + fresh.optString("status", ""));
            } catch (Exception ignore) {}
            int idx = -1;
            for (int i = 0; i < currentPackages.size(); i++) {
                String bc = firstNonEmpty(currentPackages.get(i).optString("billCode", ""),
                        currentPackages.get(i).optString("trackingNumber", ""),
                        currentPackages.get(i).optString("waybillCode", ""));
                if (billCode.equals(bc)) { idx = i; break; }
            }
            boolean replaced = false;
            if (idx >= 0) {
                currentPackages.set(idx, fresh);
                replaced = true;
            } else if ("delivered".equals(fresh.optString("status", ""))) {
                // 卡片已从列表消失：以已出库状态补回
                currentPackages.add(fresh);
                replaced = true;
            }
            if (replaced) {
                // 保持差分渲染模式（避免整批重建造成闪烁/跳动）
                boolean wasAuto = isAutoRefresh;
                isAutoRefresh = true;
                renderList();
                isAutoRefresh = wasAuto;
            }
        } catch (Throwable ignore) {}
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
            android.content.res.Resources res = ctx.getResources();
            int baseMinW = res.getDimensionPixelSize(R.dimen.grid_card_min_width);
            int gap = res.getDimensionPixelSize(R.dimen.grid_gap);
            // 优先用列表容器的实际宽度（旋转后布局已更新），避免 DisplayMetrics 旧值导致列数不刷新
            int availW;
            if (resultsContainer != null && resultsContainer.getWidth() > 0) {
                availW = resultsContainer.getWidth();
            } else {
                android.util.DisplayMetrics dm = res.getDisplayMetrics();
                int pagePadPx = res.getDimensionPixelSize(R.dimen.pad_page_h);
                availW = dm.widthPixels - pagePadPx * 2;
            }
            // 按可用宽度自适应：能放下几列就显示几列（上限 8，横屏/平板显示更多列）
            int maxCols = Math.max(1, (availW + gap) / (baseMinW + gap));
            maxCols = Math.min(maxCols, 8);
            int best = 1;
            for (int n = 2; n <= maxCols; n++) {
                int per = (availW - gap * (n - 1)) / n;
                if (per >= baseMinW) best = n;
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
            updateResultCount(currentPackages);

            if (count == 0) {
                tvNoResults.setVisibility(View.VISIBLE);
                resultsContainer.removeAllViews();
                synchronized (allImageUrls) { allImageUrls.clear(); allTrackingNos.clear(); }
                stopAllTimeoutBlink();
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
                    String resolved = "";
                    if (iurl.length() > 0) {
                        resolved = (apiService != null) ? apiService.resolveImageUrl(iurl) : iurl;
                    } else if (tno.length() > 0) {
                        // 直连模式懒加载：用已解析成功的 URL 补充预览列表（尚未滚动到的卡片暂不在其中）
                        String rawPath = firstNonEmpty(
                                item.optString("rawImgPath", ""),
                                item.optString("fileImgPath", ""),
                                item.optString("inSignImg", ""),
                                item.optString("imgName", ""));
                        String cached = resolvedImageUrls.get(imgUrlKey(tno, rawPath));
                        if (cached != null) resolved = cached;
                    }
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
                final int[] anchorOffsetHolder = {0}; // 锚点行顶到视口顶的距离，恢复时保持同一视口偏移
                final boolean[] structuralChanged = {false}; // 差分是否实际改动过列表结构（没改动则无需恢复滚动）
                if (scrollView != null && resultsContainer.getChildCount() > 0) {
                    int scrollY = scrollView.getScrollY();
                    int contentTop = resultsContainer.getTop(); // 列表容器在滚动内容中的绝对位置
                    for (int ci = 0; ci < resultsContainer.getChildCount(); ci++) {
                        View child = resultsContainer.getChildAt(ci);
                        int childAbsBottom = child.getBottom() + contentTop;
                        // 找到第一个至少部分可见的行/卡片
                        if (childAbsBottom > scrollY) {
                            anchorOffsetHolder[0] = scrollY - (child.getTop() + contentTop);
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
                                // 内容变化（如 待取件→已出库）也视为需要重建，否则状态/边框/时间不会更新
                                JSONObject newItem = itemMap.get(rowIds.get(j));
                                if (newItem == null || !isCardContentSame(card, newItem)) { same = false; break; }
                            }
                        }
                        keepRows[ri] = same;
                    }
                    for (int ri = 0; ri < newRowCount; ri++) {
                        List<String> rowIds = newRowIds.get(ri);
                        if (ri < oldRows.size() && keepRows[ri]) continue; // 内容一致，完全不动（零闪烁）
                        structuralChanged[0] = true; // 该行被重建 → 列表结构有变化
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
                            // 内容已变化（状态变化等）：丢弃旧视图，用新数据重建卡片
                            if (card != null) {
                                JSONObject newItem = itemMap.get(rowIds.get(j));
                                if (newItem != null && !isCardContentSame(card, newItem)) {
                                    card = null;
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
                        structuralChanged[0] = true;
                    }
                } else {
                    // ===== 列表模式：清空后重建 =====
                    // 先收集需要保留的卡片（清空后再加回），避免整批重建闪烁
                    int oldChildCount = resultsContainer.getChildCount();
                    List<View> survivingCards = new ArrayList<>();
                    for (int i = 0; i < resultsContainer.getChildCount(); i++) {
                        View child = resultsContainer.getChildAt(i);
                        Object tag = child.getTag(R.id.btn_query);
                        String cid = tag != null ? tag.toString() : "";
                        JSONObject newItem = itemMap.get(cid);
                        // 仅保留内容未变化的卡片；状态已变化（如 待取件→已出库）的卡片丢弃重建
                        if (newIds.contains(cid) && newItem != null && isCardContentSame(child, newItem)) {
                            survivingCards.add(child);
                        }
                    }
                    resultsContainer.removeAllViews();
                    structuralChanged[0] = survivingCards.size() < oldChildCount; // 有卡片被移除
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
                            structuralChanged[0] = true;
                        }
                    }
                }

                // 图片热更新：待取件包裹换新照片后 URL 变化 → 更新保留卡片的 ImageView 并重新加载
                // （ImageLoader 缓存按 URL 校验，URL 变化时旧图自动作废；同时同步刷新已打开的预览大图）
                refreshAllCardImages(itemMap);

                // 恢复滚动位置：找到锚点单号所在行，滚动到该行顶部
                final String anchor = anchorBillCode;
                // 结构未变化时视图原位保留，滚动位置天然不变，跳过恢复以避免任何抖动
                if (anchor != null && structuralChanged[0] && scrollView != null && resultsContainer.getChildCount() > 0) {
                    scrollView.post(() -> {
                        // 用户刚操作过列表（1秒内）：跳过滚动恢复，避免快速下滑后列表自动跳动
                        if (System.currentTimeMillis() - lastUserScrollAt < 1000) return;
                        int contentTop = resultsContainer.getTop();
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
                                // 保持重建前的视口内偏移，而不是对齐到行顶（否则列表会整体上移）
                                targetY = contentTop + child.getTop() + anchorOffsetHolder[0];
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
        // 清理已不在列表中的超时件闪烁任务
        pruneTimeoutBlink(currentPackages);
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
            // 直连模式：原始图片路径（出库换新照片后路径会变化）
            String rawImgPath = firstNonEmpty(
                    item.optString("rawImgPath", ""),
                    item.optString("fileImgPath", ""),
                    item.optString("inSignImg", ""),
                    item.optString("imgName", ""));
            if (imageUrl.length() == 0 && rawImgPath.length() == 0) return;

            ImageView iv = findCardImageView(card);
            if (iv == null) return;
            String tno = tag.toString();

            if (imageUrl.length() == 0) {
                // 直连模式：仅当原始图片路径变化时才重新解析加载（避免每次刷新重复请求 URL）
                Object rawTag = card.getTag(R.id.tag_pkg_rawpath);
                String oldRaw = (rawTag != null) ? rawTag.toString() : "";
                if (oldRaw.equals(rawImgPath)) return;
                card.setTag(R.id.tag_pkg_rawpath, rawImgPath);
                resolveAndLoad(iv, tno, rawImgPath);
                return;
            }

            String newUrl = (apiService != null) ? apiService.resolveImageUrl(imageUrl) : imageUrl;
            if (newUrl == null || newUrl.length() == 0) return;
            Object curTag = iv.getTag(R.id.image_loader_tag);
            String curUrl = (curTag != null) ? curTag.toString() : "";
            if (newUrl.equals(curUrl)) return;

            // URL 变化 → 重新加载（内存/磁盘缓存按 URL 校验，旧图自动作废，换到新照片）
            ImageLoader.with(apiService.getOkHttpClient()).load(newUrl, tno, iv, R.drawable.bg_image_placeholder);
            if (tno.length() > 0) resolvedImageUrls.put(imgUrlKey(tno, rawImgPath), newUrl);

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

    /**
     * 加载卡片图片：
     * - 已有完整 URL（服务器模式）：直接解析相对路径并加载；
     * - 仅有原始图片路径（直连模式）：按需异步解析 URL 后再加载。
     * 返回当前可用的完整 URL；未解析完成返回空串（解析成功后由回调自行加载）。
     */
    private String loadCardImage(ImageView iv, String trackingNumber, String imageUrl, String rawImgPath) {
        if (iv == null) return "";
        if (imageUrl.length() > 0 && apiService != null) {
            String resolved = apiService.resolveImageUrl(imageUrl);
            ImageLoader.with(apiService.getOkHttpClient()).load(resolved, trackingNumber, iv, R.drawable.bg_image_placeholder);
            return resolved;
        }
        iv.setImageResource(R.drawable.bg_image_placeholder);
        if (rawImgPath.length() > 0 && trackingNumber.length() > 0 && directApiClient != null) {
            String cachedUrl = resolvedImageUrls.get(imgUrlKey(trackingNumber, rawImgPath));
            if (cachedUrl != null && cachedUrl.length() > 0) {
                // 本会话已解析过相同路径：直接加载，不再重复请求 URL
                ImageLoader.with(apiService.getOkHttpClient()).load(cachedUrl, trackingNumber, iv, R.drawable.bg_image_placeholder);
            } else {
                resolveAndLoad(iv, trackingNumber, rawImgPath);
            }
        }
        return "";
    }

    /** 图片 URL 缓存键：单号 + 原始图片路径（路径变化=换新照片，旧 URL 不可复用） */
    private static String imgUrlKey(String billCode, String rawImgPath) {
        return (billCode == null ? "" : billCode) + "\u0001" + (rawImgPath == null ? "" : rawImgPath);
    }

    /**
     * 直连模式：按原始图片路径异步解析 URL 并加载。
     * 用 "raw:单号:路径" 作为 ImageView 的 URL 标记，防止卡片复用/重建后迟到的回调覆盖错误图片；
     * 解析成功的 URL 同时记入 resolvedImageUrls，供预览列表重建时补充。
     */
    private void resolveAndLoad(final ImageView iv, final String billCode, final String rawImgPath) {
        if (iv == null || billCode == null || billCode.length() == 0 || rawImgPath == null || rawImgPath.length() == 0) return;
        final String marker = "raw:" + billCode + ":" + rawImgPath;
        iv.setTag(R.id.image_loader_tag, marker);
        try {
            if (directApiClient == null) return;
            directApiClient.resolveImageUrl(billCode, rawImgPath, new DirectApiClient.ImageUrlCallback() {
                @Override
                public void onUrl(final String url) {
                    if (!isViewReady) return;
                    mainHandler.post(() -> {
                        try {
                            if (url == null || url.length() == 0) return;
                            if (iv.getTag(R.id.image_loader_tag) == null) return;
                            if (!marker.equals(iv.getTag(R.id.image_loader_tag))) return; // 卡片已复用/重建，丢弃迟到结果
                            if (apiService == null) return;
                            ImageLoader.with(apiService.getOkHttpClient()).load(url, billCode, iv, R.drawable.bg_image_placeholder);
                            // 记录已解析 URL：预览列表重建时使用
                            if (billCode.length() > 0) resolvedImageUrls.put(imgUrlKey(billCode, rawImgPath), url);
                            synchronized (allImageUrls) {
                                if (!allImageUrls.contains(url)) {
                                    allImageUrls.add(url);
                                    allTrackingNos.add(billCode);
                                }
                            }
                        } catch (Throwable ignore) {}
                    });
                }

                @Override
                public void onError(String error) {
                    Log.w(TAG, "图片URL解析失败 billCode=" + billCode + " err=" + error);
                }
            });
        } catch (Throwable ignore) {}
    }

    /** 异步解析"另一张照片"URL（入库照/出库照），结果记入 comparePhotoMap 供预览切换 */
    private void resolveComparePhoto(final String billCode, final String rawPath, final String name) {
        if (billCode == null || billCode.isEmpty() || rawPath == null || rawPath.isEmpty()) {
            comparePhotoMap.remove(billCode);
            return;
        }
        try {
            if (directApiClient == null) return;
            directApiClient.resolveImageUrl(billCode, rawPath, new DirectApiClient.ImageUrlCallback() {
                @Override public void onUrl(final String url) {
                    if (!isViewReady) return;
                    mainHandler.post(() -> {
                        try {
                            if (url == null || url.length() == 0) {
                                comparePhotoMap.remove(billCode);
                                return;
                            }
                            comparePhotoMap.put(billCode, new String[]{url, name});
                        } catch (Throwable ignore) {}
                    });
                }
                @Override public void onError(String error) {
                    // 对比照片解析失败：保持无对比图
                    if (!isViewReady) return;
                    mainHandler.post(() -> comparePhotoMap.remove(billCode));
                }
            });
        } catch (Throwable ignore) {}
    }

    /** 计算并准备"另一张照片"（出入库对比）：直连模式按原始路径异步解析，服务器模式直接用返回的URL */
    private void prepareComparePhoto(String trackingNumber, JSONObject item, String displayRaw) {
        if (trackingNumber == null || trackingNumber.isEmpty() || item == null) return;
        try {
            String arrivalRaw = firstNonEmpty(
                    item.optString("rawImgPathArrival", ""),
                    item.optString("fileImgPath", ""),
                    item.optString("imgName", ""));
            String outboundRaw = firstNonEmpty(
                    item.optString("rawImgPathOutbound", ""),
                    item.optString("inSignImg", ""));

            // 服务器模式：服务器已返回 imageUrl（一般为入库照），出库照可能由独立字段给出
            String serverOutbound = firstNonEmpty(
                    item.optString("outboundImageUrl", ""),
                    item.optString("signImageUrl", ""),
                    item.optString("signedImageUrl", ""),
                    item.optString("outImgUrl", ""));
            if (serverOutbound.length() > 0) {
                String resolved = (apiService != null) ? apiService.resolveImageUrl(serverOutbound) : serverOutbound;
                if (resolved != null && resolved.length() > 0) {
                    comparePhotoMap.put(trackingNumber, new String[]{resolved, "出库图片"});
                }
                return;
            }

            // 直连模式：判断当前显示的原始路径是哪一张，另一张作为对比
            String secondaryRaw = "";
            String secondaryName = "";
            if (displayRaw != null && displayRaw.length() > 0 && displayRaw.equals(arrivalRaw)) {
                secondaryRaw = outboundRaw;
                secondaryName = "出库图片";
            } else if (displayRaw != null && displayRaw.length() > 0 && displayRaw.equals(outboundRaw)) {
                secondaryRaw = arrivalRaw;
                secondaryName = "入库图片";
            } else {
                // 无法判断：取另一个非空路径
                if (arrivalRaw.length() > 0) { secondaryRaw = arrivalRaw; secondaryName = "入库图片"; }
                else if (outboundRaw.length() > 0) { secondaryRaw = outboundRaw; secondaryName = "出库图片"; }
            }
            resolveComparePhoto(trackingNumber, secondaryRaw, secondaryName);
        } catch (Throwable ignore) {}
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
        // 快速滑动过程中（400ms内）不触发底部加载，等滚动稳定后再加载，避免与惯性滚动相互干扰
        if (System.currentTimeMillis() - lastUserScrollAt < 400) return;
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

    /** 打开预览大图并附带"出入库照片对比"列表（与 allImageUrls 顺序一致） */
    private void showPreviewWithCompare(Context ctx, List<String> urlsCopy, List<String> nosCopy, int idx, OkHttpClient cl) {
        List<String> cmpUrls = new ArrayList<>();
        List<String> cmpNames = new ArrayList<>();
        List<String> primaryNames = new ArrayList<>();
        for (String no : nosCopy) {
            String[] c = (no == null) ? null : comparePhotoMap.get(no);
            if (c != null && c[0] != null && c[0].length() > 0) {
                cmpUrls.add(c[0]);
                String cmpName = (c[1] == null || c[1].isEmpty()) ? "对比图片" : c[1];
                cmpNames.add(cmpName);
                // 当前显示的照片名称 = 对比照片的反面（入库↔出库）
                primaryNames.add("出库图片".equals(cmpName) ? "入库图片" : "出库图片");
            } else {
                cmpUrls.add("");
                cmpNames.add("");
                primaryNames.add("");
            }
        }
        ImagePreviewDialog.show(ctx, urlsCopy, idx, nosCopy, cl, cmpUrls, cmpNames, primaryNames);
    }

    /** 判断卡片内容是否与新数据一致（当前比较状态；图片 URL 变化由 refreshAllCardImages 热更新） */
    private boolean isCardContentSame(View card, JSONObject item) {
        if (card == null || item == null) return false;
        try {
            Object t = card.getTag(R.id.tag_pkg_status);
            String oldStatus = (t == null) ? "" : t.toString();
            String newStatus = item.optString("status", "pending");
            return oldStatus.equals(newStatus);
        } catch (Throwable ignore) {
            return false;
        }
    }

    // ===== 超时件判定与三层标注 =====

    /** 读取设置"标注最近N天的超时件"（1~20，默认3） */
    private int getTimeoutMarkDays() {
        try {
            SharedPreferences prefs = requireContext().getSharedPreferences("chajianzhushou_prefs", Context.MODE_PRIVATE);
            int v = prefs.getInt(KEY_TIMEOUT_MARK_DAYS, DEFAULT_TIMEOUT_MARK_DAYS);
            if (v < 1) v = 1;
            if (v > 20) v = 20;
            return v;
        } catch (Exception e) {
            return DEFAULT_TIMEOUT_MARK_DAYS;
        }
    }

    /** 读取设置"显示超时件标注"总开关（默认开启） */
    private boolean getTimeoutMarkEnabled() {
        try {
            SharedPreferences prefs = requireContext().getSharedPreferences("chajianzhushou_prefs", Context.MODE_PRIVATE);
            return prefs.getBoolean(KEY_TIMEOUT_MARK_ENABLED, true);
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * 超时件判定（两个条件全部满足）：
     * 1) remark == "超时出库"；
     * 2) 出库时间在"设置标注最近N天"范围内。
     * remark 为"超时出库"但超出 N 天 → 按普通已出库处理。
     */
    private boolean isTimeoutPackage(JSONObject item) {
        if (item == null) return false;
        try {
            if (!getTimeoutMarkEnabled()) return false; // "显示超时件标注"关闭：全部按普通包裹处理
            if (!"超时出库".equals(item.optString("remark", ""))) return false;
            String outTime = firstNonEmpty(
                    item.optString("outboundTime", ""),
                    item.optString("takeDate", ""),
                    item.optString("deliveryTime", ""),
                    item.optString("deliveredTime", ""),
                    item.optString("outTime", ""),
                    item.optString("outboundAt", ""));
            long t = parseTimeMillis(outTime);
            if (t <= 0) return false;
            long cutoff = System.currentTimeMillis() - getTimeoutMarkDays() * 24L * 3600 * 1000L;
            return t >= cutoff;
        } catch (Throwable ignore) {
            return false;
        }
    }

    /** 解析时间字符串为毫秒；支持 yyyy-MM-dd HH:mm:ss、yyyy-MM-dd、紧凑数字、纯时间戳等。 */
    private static long parseTimeMillis(String raw) {
        if (raw == null) return 0;
        String s = raw.trim();
        if (s.length() == 0) return 0;
        try {
            if (s.matches("\\d{13,}")) return Long.parseLong(s);
            if (s.matches("\\d{10}")) return Long.parseLong(s) * 1000L;
            if (s.matches("\\d{14}")) {
                java.util.Date dt = new java.text.SimpleDateFormat("yyyyMMddHHmmss", Locale.getDefault()).parse(s);
                return dt == null ? 0 : dt.getTime();
            }
            if (s.matches("\\d{8}")) {
                java.util.Date dt = new java.text.SimpleDateFormat("yyyyMMdd", Locale.getDefault()).parse(s);
                return dt == null ? 0 : dt.getTime();
            }
            String norm = s.replace('T', ' ').replace('/', '-');
            int plus = norm.indexOf('+');
            if (plus > 0) norm = norm.substring(0, plus);
            int zIdx = norm.indexOf('Z');
            if (zIdx > 0) norm = norm.substring(0, zIdx);
            norm = norm.trim();
            String[] patterns = {"yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd"};
            for (String p : patterns) {
                try {
                    java.text.SimpleDateFormat f = new java.text.SimpleDateFormat(p, Locale.getDefault());
                    f.setLenient(false);
                    java.util.Date dt = f.parse(norm);
                    if (dt != null) return dt.getTime();
                } catch (Exception ignore) {}
            }
        } catch (Throwable ignore) {}
        return 0;
    }

    /** 计算"出库时间距离今天"的天数（按自然日差，同一天为0天） */
    private static int daysSinceOutbound(String outTime) {
        long t = parseTimeMillis(outTime);
        if (t <= 0) return 0;
        try {
            java.util.Calendar cal = java.util.Calendar.getInstance();
            long now = cal.getTimeInMillis();
            cal.setTimeInMillis(t);
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0);
            cal.set(java.util.Calendar.MINUTE, 0);
            cal.set(java.util.Calendar.SECOND, 0);
            cal.set(java.util.Calendar.MILLISECOND, 0);
            long dayOut = cal.getTimeInMillis();
            java.util.Calendar calNow = java.util.Calendar.getInstance();
            calNow.set(java.util.Calendar.HOUR_OF_DAY, 0);
            calNow.set(java.util.Calendar.MINUTE, 0);
            calNow.set(java.util.Calendar.SECOND, 0);
            calNow.set(java.util.Calendar.MILLISECOND, 0);
            long dayNow = calNow.getTimeInMillis();
            long days = (dayNow - dayOut) / (24L * 3600 * 1000L);
            return (int) Math.max(0, days);
        } catch (Throwable ignore) {
            return 0;
        }
    }

    /** 出库时间外圈"流水灯"标注：黄色流动边框包裹时间 chip。 */
    private FrameLayout wrapTimeWithFlowBorder(Context ctx, TextView timeChip, LinearLayout.LayoutParams outerLp) {
        FrameLayout frame = new FrameLayout(ctx);
        frame.setLayoutParams(outerLp);
        // 时间 chip 在框内使用"无 margin"的布局参数：
        // 原 topMargin=6dp 若保留，会把 chip 在框内下推 6dp，导致流水灯相对偏上
        frame.addView(timeChip, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // 后加 FlowBorderView，按 chip 实际尺寸贴合绘制
        FlowBorderView flow = new FlowBorderView(ctx);
        FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        flow.setLayoutParams(fp);
        frame.addView(flow);
        return frame;
    }

    /** 超时件标签外圈"流水灯"标注：与出库时间一致，黄色流动边框包裹标签（不再闪烁） */
    private FrameLayout wrapTagWithFlowBorder(Context ctx, TextView tag, LinearLayout.LayoutParams outerLp) {
        FrameLayout frame = new FrameLayout(ctx);
        frame.setLayoutParams(outerLp);
        frame.addView(tag, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        FlowBorderView flow = new FlowBorderView(ctx);
        flow.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        frame.addView(flow);
        return frame;
    }

    /** 超时件黄色"超时出库 x天"标签（x=出库时间距离今天的天数，紧挨"已出库"状态文字） */
    private TextView makeTimeoutTag(Context ctx, android.content.res.Resources res, boolean vertical, int days) {
        TextView tag = new TextView(ctx);
        tag.setText("超时出库 " + days + "天");
        tag.setTextColor(ctx.getResources().getColor(R.color.warning, ctx.getTheme()));
        tag.setBackgroundResource(R.drawable.bg_status_timeout);
        tag.setTextSize(14f); // 字号比普通状态标签稍大，突出超时件
        tag.setTypeface(Typeface.DEFAULT_BOLD);
        tag.setPadding(res.getDimensionPixelSize(R.dimen.chip_padding_h),
                res.getDimensionPixelSize(R.dimen.chip_padding_v),
                res.getDimensionPixelSize(R.dimen.chip_padding_h),
                res.getDimensionPixelSize(R.dimen.chip_padding_v));
        return tag;
    }

    /** 启动超时件卡片黄色边框缓慢闪烁（标签不再闪烁，改用流水灯边框） */
    private void startTimeoutBlink(final CardView card, final String billCode) {
        Runnable old = billCode == null ? null : timeoutBlinkMap.remove(billCode);
        if (old != null) {
            try { mainHandler.removeCallbacks(old); } catch (Exception ignore) {}
        }
        if (card == null) return;
        final boolean[] on = {true};
        Runnable blink = new Runnable() {
            @Override
            public void run() {
                if (!isViewReady) return;
                on[0] = !on[0];
                try {
                    card.setBackgroundResource(on[0] ? R.drawable.bg_pkg_card_timeout : R.drawable.bg_pkg_card);
                } catch (Throwable ignore) {}
                if (isViewReady) mainHandler.postDelayed(this, 700);
            }
        };
        if (billCode != null) timeoutBlinkMap.put(billCode, blink);
        mainHandler.post(blink);
    }

    /** 停止某个单号的闪烁任务 */
    private void stopTimeoutBlink(String billCode) {
        if (billCode == null) return;
        Runnable old = timeoutBlinkMap.remove(billCode);
        if (old != null) {
            try { mainHandler.removeCallbacks(old); } catch (Exception ignore) {}
        }
    }

    /** 停止全部闪烁任务（页面销毁时调用） */
    private void stopAllTimeoutBlink() {
        for (Runnable r : timeoutBlinkMap.values()) {
            try { mainHandler.removeCallbacks(r); } catch (Exception ignore) {}
        }
        timeoutBlinkMap.clear();
    }

    /** 渲染完成后清理：列表中已不存在的包裹停止闪烁，避免任务残留。 */
    private void pruneTimeoutBlink(List<JSONObject> packages) {
        if (timeoutBlinkMap.isEmpty()) return;
        java.util.Set<String> alive = new java.util.HashSet<>();
        if (packages != null) {
            for (JSONObject p : packages) {
                String bc = firstNonEmpty(p.optString("billCode", ""),
                        p.optString("trackingNumber", ""),
                        p.optString("waybillCode", ""));
                if (bc.length() > 0) alive.add(bc);
            }
        }
        java.util.Iterator<java.util.Map.Entry<String, Runnable>> it = timeoutBlinkMap.entrySet().iterator();
        while (it.hasNext()) {
            java.util.Map.Entry<String, Runnable> e = it.next();
            if (!alive.contains(e.getKey())) {
                try { mainHandler.removeCallbacks(e.getValue()); } catch (Exception ignore) {}
                it.remove();
            }
        }
    }

    /**
     * 结果数框：横向单行显示
     * "xx 个包裹 · 待取 x（绿色数字） · 超时出库 x（黄色数字）"。
     * 超时数量跟随设置"显示超时件标注"（关闭时为0）。
     */
    private void updateResultCount(List<JSONObject> packages) {
        if (tvResultCount == null) return;
        try {
            Context ctx = getContext();
            if (ctx == null) return;
            int total = packages == null ? 0 : packages.size();
            int pending = 0;
            int timeout = 0;
            if (packages != null) {
                for (JSONObject p : packages) {
                    if ("pending".equals(p.optString("status", ""))) pending++;
                    else if (isTimeoutPackage(p)) timeout++;
                }
            }
            int muted = getResources().getColor(R.color.muted, ctx.getTheme());
            int success = getResources().getColor(R.color.success, ctx.getTheme());
            int warning = getResources().getColor(R.color.warning, ctx.getTheme());

            SpannableStringBuilder sb = new SpannableStringBuilder();
            sb.append(total + " 个包裹 · 待取 ");
            int n1 = sb.length();
            sb.append(String.valueOf(pending));
            sb.append(" · 超时出库 ");
            int n2 = sb.length();
            sb.append(String.valueOf(timeout));
            // 整段默认 muted 色，两个数字分别上绿色/黄色
            sb.setSpan(new ForegroundColorSpan(muted), 0, sb.length(),
                    SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new ForegroundColorSpan(success), n1, n1 + String.valueOf(pending).length(),
                    SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new ForegroundColorSpan(warning), n2, n2 + String.valueOf(timeout).length(),
                    SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
            tvResultCount.setText(sb);
        } catch (Throwable ignore) {}
    }

    // ===== 查询历史（点击输入框展开最近查询） =====

    private List<JSONObject> loadQueryHistory() {
        List<JSONObject> list = new ArrayList<>();
        try {
            SharedPreferences prefs = requireContext().getSharedPreferences("chajianzhushou_prefs", Context.MODE_PRIVATE);
            String raw = prefs.getString(PREFS_HISTORY, "");
            if (raw != null && raw.length() > 0) {
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o != null) list.add(o);
                }
            }
        } catch (Throwable ignore) {}
        return list;
    }

    private void saveQueryHistory(List<JSONObject> list) {
        try {
            JSONArray arr = new JSONArray();
            for (JSONObject o : list) arr.put(o);
            requireContext().getSharedPreferences("chajianzhushou_prefs", Context.MODE_PRIVATE)
                    .edit().putString(PREFS_HISTORY, arr.toString()).apply();
        } catch (Throwable ignore) {}
    }

    /** 记录一条查询历史：相同"值+类型"去重并移到最前，超出上限裁剪 */
    private void recordQueryHistory(String value, String type) {
        if (value == null || value.isEmpty()) return;
        try {
            List<JSONObject> list = loadQueryHistory();
            list.removeIf(o -> value.equals(o.optString("v", "")) && type.equals(o.optString("t", "")));
            JSONObject o = new JSONObject();
            o.put("v", value);
            o.put("t", type);
            list.add(0, o);
            while (list.size() > MAX_HISTORY) list.remove(list.size() - 1);
            saveQueryHistory(list);
        } catch (Throwable ignore) {}
    }

    /** 渲染最近查询面板：点击记录自动回填输入框、选中对应类型并自动查询 */
    private void renderHistory() {
        if (historyPanel == null) return;
        historyPanel.removeAllViews();
        List<JSONObject> list = loadQueryHistory();
        if (list.isEmpty()) {
            historyPanel.setVisibility(View.GONE);
            return;
        }
        historyPanel.setVisibility(View.VISIBLE);
        Context ctx = getContext();
        if (ctx == null) return;
        try {
            android.content.res.Resources res = ctx.getResources();
            int ink2 = getResources().getColor(R.color.ink2, ctx.getTheme());
            int muted = getResources().getColor(R.color.muted, ctx.getTheme());
            int padH = res.getDimensionPixelSize(R.dimen.spacing_lg);
            int padV = res.getDimensionPixelSize(R.dimen.spacing_lg);

            // 面板标题
            TextView header = new TextView(ctx);
            header.setText("最近查询");
            header.setTextColor(muted);
            header.setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.txt_sm));
            header.setTypeface(Typeface.DEFAULT_BOLD);
            header.setPadding(padH, padV, padH, padV);
            historyPanel.addView(header);

            // 横向滚动容器：每条记录一个圆角轮廓 chip
            HorizontalScrollView hsv = new HorizontalScrollView(ctx);
            hsv.setHorizontalScrollBarEnabled(false);
            LinearLayout chipRow = new LinearLayout(ctx);
            chipRow.setOrientation(LinearLayout.HORIZONTAL);
            chipRow.setGravity(Gravity.CENTER_VERTICAL);
            hsv.addView(chipRow);
            historyPanel.addView(hsv, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            int chipPadH = res.getDimensionPixelSize(R.dimen.spacing_lg);
            int chipPadV = res.getDimensionPixelSize(R.dimen.spacing_sm);
            int margin = res.getDimensionPixelSize(R.dimen.spacing_md);
            int strokePx = Math.max(1, res.getDimensionPixelSize(R.dimen.divider_height));
            int borderColor = getResources().getColor(R.color.hair2, ctx.getTheme());

            int shown = 0;
            for (JSONObject o : list) {
                if (shown >= HISTORY_PANEL_MAX_ROWS) break;
                final String v = o.optString("v", "");
                final String t = o.optString("t", "");
                if (v.isEmpty()) continue;
                String prefix = "phoneTail".equals(t) ? "手机尾号 " : ("pickupCode".equals(t) ? "取件码 " : "运单号 ");

                TextView chip = new TextView(ctx);
                chip.setText(prefix + v);
                chip.setTextColor(ink2);
                chip.setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.txt_sm));
                chip.setSingleLine(true);
                chip.setEllipsize(android.text.TextUtils.TruncateAt.END);
                chip.setMaxWidth(res.getDimensionPixelSize(R.dimen.chip_max_width));
                // 圆角轮廓
                android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
                bg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
                bg.setCornerRadius(res.getDimensionPixelSize(R.dimen.radius_md));
                bg.setColor(0x00000000);
                bg.setStroke(strokePx, borderColor);
                chip.setBackground(bg);
                chip.setPadding(chipPadH, chipPadV, chipPadH, chipPadV);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.rightMargin = margin;
                chip.setLayoutParams(lp);
                chip.setClickable(true);
                chip.setOnClickListener(vv -> {
                    try {
                        if (etBillCode != null) etBillCode.setText(v);
                        setSearchType(t);
                        hideHistoryPanel();
                        performQuery(true);
                    } catch (Throwable ignore) {}
                });
                chipRow.addView(chip);
                shown++;
            }
        } catch (Throwable ignore) {}
    }

    /** 收起查询历史面板 */
    private void hideHistoryPanel() {
        if (historyPanel != null) historyPanel.setVisibility(View.GONE);
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
        // 直连模式：查询结果只带原始图片路径，URL 由渲染时按需解析（随滚动分批）
        String rawImgPath = firstNonEmpty(
                item.optString("rawImgPath", ""),
                item.optString("fileImgPath", ""),
                item.optString("inSignImg", ""),
                item.optString("imgName", ""));
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
        boolean timeout = "delivered".equals(status) && isTimeoutPackage(item);
        // 普通"已出库"卡片整体稍微调暗，与待取件区分；超时件保持高亮（另有标注效果）
        if ("delivered".equals(status) && !timeout) {
            card.setAlpha(0.8f);
        }
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
        card.setTag(R.id.tag_pkg_rawpath, rawImgPath);

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
        // 图片加载：有完整URL（服务器模式）直接加载；只有原始路径（直连模式）按需异步解析后再加载
        final String resolvedImageUrl = loadCardImage(iv, trackingNumber, imageUrl, rawImgPath);
        // 出入库照片对比：准备"另一张照片"（入库照/出库照），供预览大图切换
        prepareComparePhoto(trackingNumber, item, rawImgPath);
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
                    showPreviewWithCompare(getContext(), urlsCopy, nosCopy, idx, cl);
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

        // 单号行标签与值的间距单独再缩 2dp（4dp→2dp），其余行保持 4dp
        int gap2 = (int) (ctx.getResources().getDisplayMetrics().density * 2 + 0.5f);
        LinearLayout billRow = makeLabelValueRow(ctx, "单号", trackingNumber, ink2, champagne, ink, true, dp6, 14, 16, true);
        setLabelGap(billRow, gap2);
        info.addView(billRow);
        makeValueClickableToCopy(billRow, ctx, "单号", trackingNumber); // 点击单号直接复制
        // 收件人(姓名+手机号)：网格窄卡下强制单行省略号，避免折行撑高卡片导致同行不齐
        StringBuilder who = new StringBuilder(recipient);
        if (mobile.length() > 0) who.append("  ").append(mobile);
        addLabelValueRow(info, "收件人", who.toString(), ink2, ink, ink, false, dp6, 14, 16, true);

        // 无论 pickUpCode 是否为空都固定显示一行，保证 info 区行数一致
        LinearLayout pickRow = makeLabelValueRow(ctx, "取件码", pickupCode,
                ink2, accent, ink, false, dp6, 14, 20, true);
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
        makeValueClickableToCopy(pickRow, ctx, "取件码", pickupCode); // 点击取件码直接复制

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
        String timeLabel = isDelivered ? "出库 " : "入库 ";
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
        if (timeout) {
            // 超时件：出库时间外圈加"流水灯"黄色流动边框（③）
            metaWrap.addView(wrapTimeWithFlowBorder(ctx, timeChip, tLp));
        } else {
            metaWrap.addView(timeChip);
        }
        info.addView(metaWrap);

        // ===== Status tag =====
        TextView statusTag = new TextView(ctx);
        boolean pending = "pending".equals(status);
        // 记录待取状态 + 应用"显示已出库激活时"的绿色边框标识
        card.setTag(R.id.tag_pkg_pending, pending);
        card.setTag(R.id.tag_pkg_status, status == null ? "" : status);
        applyCardPendingBorder(card, pending);
        statusTag.setText(pending ? "待取件" : ("delivered".equals(status) ? "已出库" : status));
        statusTag.setTextColor(pending ? accent : danger);
        statusTag.setBackgroundResource(pending ? R.drawable.bg_status_pending : R.drawable.bg_status_delivered);
        statusTag.setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.chip_text_size));
        statusTag.setTypeface(Typeface.DEFAULT_BOLD);
        statusTag.setPadding(res.getDimensionPixelSize(R.dimen.chip_padding_h), res.getDimensionPixelSize(R.dimen.chip_padding_v), res.getDimensionPixelSize(R.dimen.chip_padding_h), res.getDimensionPixelSize(R.dimen.chip_padding_v));

        // 状态区：待取件/已出库文字 + 超时件黄色"超时出库"标签（②）
        LinearLayout statusBox = new LinearLayout(ctx);
        statusBox.setOrientation(vertical ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        statusBox.setGravity(vertical ? Gravity.CENTER_HORIZONTAL : Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (vertical) stLp.topMargin = dp10;
        statusBox.setLayoutParams(stLp);
        statusBox.addView(statusTag);
        if (timeout) {
            TextView timeoutTag = makeTimeoutTag(ctx, res, vertical, daysSinceOutbound(outboundTime));
            LinearLayout.LayoutParams tagLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (vertical) tagLp.leftMargin = res.getDimensionPixelSize(R.dimen.spacing_sm);
            else tagLp.topMargin = res.getDimensionPixelSize(R.dimen.spacing_sm);
            statusBox.addView(wrapTagWithFlowBorder(ctx, timeoutTag, tagLp));
        }
        root.addView(info);
        root.addView(statusBox);

        // 超时件：卡片黄色边框缓慢闪烁（①）
        if (timeout) {
            startTimeoutBlink(card, trackingNumber);
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
            // 直连模式：查询结果只带原始图片路径，URL 由渲染时按需解析（随滚动分批）
            String rawImgPath = firstNonEmpty(
                    item.optString("rawImgPath", ""),
                    item.optString("fileImgPath", ""),
                    item.optString("inSignImg", ""),
                    item.optString("imgName", ""));
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
            boolean timeout = "delivered".equals(status) && isTimeoutPackage(item);
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
            // 普通"已出库"卡片整体稍微调暗，与待取件区分；超时件保持高亮（另有标注效果）
            if ("delivered".equals(status) && !timeout) {
                card.setAlpha(0.8f);
            }

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
            // 图片加载：有完整URL（服务器模式）直接加载；只有原始路径（直连模式）按需异步解析后再加载
            final String resolvedImageUrl = loadCardImage(iv, trackingNumber, imageUrl, rawImgPath);
            // 出入库照片对比：准备"另一张照片"（入库照/出库照），供预览大图切换
            prepareComparePhoto(trackingNumber, item, rawImgPath);
            // 点击图片放大预览：使用 allImageUrls/allTrackingNos 全量列表，支持跨包裹上下张翻页
            iv.setClickable(true);
            iv.setFocusable(true);
            iv.setOnClickListener(v -> {
                try {
                    OkHttpClient cl = apiService != null ? apiService.getOkHttpClient() : null;
                    // 点击时读取 ImageView 当前 URL（懒加载解析完成后即为真实URL），保证预览到最新图
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
                        showPreviewWithCompare(getContext(), urlsCopy, nosCopy, idx, cl);
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
            // 单号行标签与值的间距单独再缩 2dp（4dp→2dp），其余行保持 4dp
            int gap2 = (int) (ctx.getResources().getDisplayMetrics().density * 2 + 0.5f);
            LinearLayout billRow = makeLabelValueRow(ctx, "单号", trackingNumber, ink2, champagne, ink, true, dp6, 14, 16, true);
            setLabelGap(billRow, gap2);
            info.addView(billRow);
            makeValueClickableToCopy(billRow, ctx, "单号", trackingNumber); // 点击单号直接复制

            // Recipient row：网格下单行省略，避免折行导致同行卡片高度不齐
            StringBuilder who = new StringBuilder(recipient);
            if (mobile.length() > 0) who.append("  ").append(mobile);
            addLabelValueRow(info, "收件人", who.toString(), ink2, ink, ink, false, dp6, 14, 16, true);

            // Pickup code row（vertical 模式强制固定一行，保证信息区高度一致）
            LinearLayout pickRow = makeLabelValueRow(ctx, "取件码", pickupCode,
                    ink2, accent, ink, false, dp6, 14, 20, true);
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
            makeValueClickableToCopy(pickRow, ctx, "取件码", pickupCode); // 点击取件码直接复制

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
            String timeLabel2 = delivered2 ? "出库 " : "入库 ";
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
            if (timeout) {
                // 超时件：出库时间外圈加"流水灯"黄色流动边框（③）
                metaWrap.addView(wrapTimeWithFlowBorder(ctx, timeChip, tLp2));
            } else {
                metaWrap.addView(timeChip);
            }
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
            statusTag.setTextSize(14f);
            statusTag.setTypeface(Typeface.DEFAULT_BOLD);
            statusTag.setPadding((int) (12 * d + 0.5f), (int) (6 * d + 0.5f), (int) (12 * d + 0.5f), (int) (6 * d + 0.5f));

            // 状态区：待取件/已出库文字 + 超时件黄色"超时出库"标签（②）
            LinearLayout statusBox = new LinearLayout(ctx);
            statusBox.setOrientation(vertical ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
            statusBox.setGravity(vertical ? Gravity.CENTER_HORIZONTAL : Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (vertical) stLp.topMargin = dp10;
            statusBox.setLayoutParams(stLp);
            statusBox.addView(statusTag);
            if (timeout) {
                TextView timeoutTag = makeTimeoutTag(ctx, res, vertical, daysSinceOutbound(outboundTime));
                LinearLayout.LayoutParams tagLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (vertical) tagLp.leftMargin = res.getDimensionPixelSize(R.dimen.spacing_sm);
                else tagLp.topMargin = res.getDimensionPixelSize(R.dimen.spacing_sm);
                statusBox.addView(wrapTagWithFlowBorder(ctx, timeoutTag, tagLp));
            }
            root.addView(info);
            root.addView(statusBox);

            // 超时件：卡片黄色边框缓慢闪烁（①）
            if (timeout) {
                startTimeoutBlink(card, trackingNumber);
            }

            // Set tag for differential refresh
            card.setTag(R.id.btn_query, trackingNumber);
            card.setTag(R.id.tag_pkg_status, status == null ? "" : status);
            card.setTag(R.id.tag_pkg_rawpath, rawImgPath);
            resultsContainer.addView(card);
        } catch (Exception e) {
            safeToast("创建卡片失败: " + e.getMessage());
        }
    }

    // ===== Label/Value Row Helpers =====

    /** 单独调整某行"标签-值"的间距（用于单号行更紧凑，不影响其他行） */
    private static void setLabelGap(LinearLayout row, int rightMarginPx) {
        if (row == null || row.getChildCount() == 0) return;
        View label = row.getChildAt(0);
        ViewGroup.LayoutParams lp = label.getLayoutParams();
        if (lp instanceof LinearLayout.LayoutParams) {
            ((LinearLayout.LayoutParams) lp).rightMargin = rightMarginPx;
            label.setLayoutParams(lp);
        }
    }

    /** 让某行"值"文字可点击：点击直接复制该值（单号/取件码等），收件人报码时更快 */
    private static void makeValueClickableToCopy(final LinearLayout row, final Context ctx,
                                                final String label, final String value) {
        if (row == null || row.getChildCount() < 2) return;
        View vv = row.getChildAt(1);
        if (!(vv instanceof TextView)) return;
        ((TextView) vv).setClickable(true);
        ((TextView) vv).setOnClickListener(v -> {
            if (value == null || value.isEmpty()) return;
            try {
                ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("查件助手", value));
                    Toast.makeText(ctx, "已复制" + label + "：" + value, Toast.LENGTH_SHORT).show();
                    try { LogRecorder.info(ctx, "QUERY", "复制" + label, value); } catch (Exception ignore) {}
                }
            } catch (Throwable ignore) {}
        });
    }

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
        // 标签与值之间的间隔：6dp→4dp，进一步缩小"单号"等标签与值之间的空隙
        llp.rightMargin = ctx.getResources().getDimensionPixelSize(R.dimen.spacing_xs);
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
