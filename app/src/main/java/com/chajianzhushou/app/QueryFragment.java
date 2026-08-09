package com.chajianzhushou.app;

import android.content.Context;
import android.content.Intent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.net.Uri;
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
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.widget.SwitchCompat;
import androidx.cardview.widget.CardView;
import androidx.core.content.FileProvider;
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
    private static final String KEY_AUTO_REFRESH_MAX = "auto_refresh_max_count";
    private static final String KEY_GRID_MANUAL_ENABLED = "grid_manual_columns_enabled";
    private static final String KEY_GRID_MANUAL_COLUMNS_PORTRAIT = "grid_manual_columns_portrait";
    private static final String KEY_GRID_MANUAL_COLUMNS_LANDSCAPE = "grid_manual_columns_landscape";

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
    private TextView tvResultTimeout;
    private FlowBorderView resultCountFlow;
    private View resultCountMarquee;
    private View resultCountBox;
    // 是否已经执行过查询：未查询前整个结果数框（前缀+超时出库文字+跑马灯）保持隐藏
    private boolean hasQueried = false;
    private LinearLayout resultsContainer;
    private LinearLayout historyPanel;
    private QueryHistoryPanel historyPanelHelper; // 查询历史面板（存储/渲染/长按删除）
    private PackageCardFactory packageCardFactory; // 包裹卡片构建（视图/闪烁/行辅助）
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
    // 最后一次手动查询使用的类型：自动刷新固定用它，手动切换类型不影响已开始的自动刷新
    private String lastQueriedType = "phoneTail";
    // 当前列表对应的查询条件（单号+类型）：自动刷新合并"已出库"时校验是否同一条件，条件变了不合并
    private String listQueryKey = "";
    // 刚刚出库标记：本次会话中由"待取件"变成"已出库"的包裹单号集合（手动重新查询时清空）
    private final java.util.Set<String> justOutboundBillCodes = new java.util.HashSet<>();
    // 渲染代数：renderList 每次执行自增，用于让过期的异步回调（如颗粒消失动画）不再重复渲染
    private int renderGeneration = 0;
    // 上次渲染时的网格配置指纹：切回本页时若配置没变则跳过整表重建，避免列表图片重复加载
    private String lastGridRenderKey = "";
    private boolean isGridView = false;
    private boolean showDelivered = true;
    private boolean isAutoRefresh = false;
    private int lastPendingCount = -1;
    // 自动刷新已执行轮次：手动查询重置，达到设置上限后自动暂停
    private int autoRefreshTickCount = 0;
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
    private static final int BATCH_SIZE = 30;
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
    private ImageUrlResolver imageUrlResolver;
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

    // Auto-refresh（调度与指示器由 AutoRefreshController 负责）
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private AutoRefreshController autoRefreshController;
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

    // 拍照出库：调系统相机拍照 → 压缩 → 上传照片 → 出库
    private final androidx.activity.result.ActivityResultLauncher<Uri> picOutboundLauncher =
            registerForActivityResult(new ActivityResultContracts.TakePicture(), result -> {
                if (!isViewReady) return;
                if (result != null && result) {
                    onPhotoCaptured();
                } else {
                    safeToast("已取消拍照");
                }
            });
    private String picOutboundPendingBillCode;
    private JSONObject picOutboundPendingItem;

    // 输入框连续输入（多手机尾号标签模式）
    private LinearLayout tailTagsContainer;
    private android.widget.HorizontalScrollView tailTagsScroll;
    private final java.util.List<String> tailTags = new ArrayList<>();
    private String currentTailTag;
    private boolean multiTailEnabled = false;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_query, container, false);

        // Find all views
        scrollView = view.findViewById(R.id.scroll_query);
        etBillCode = view.findViewById(R.id.et_bill_code);
        tailTagsContainer = view.findViewById(R.id.tail_tags_container);
        tailTagsScroll = view.findViewById(R.id.tail_tags_scroll);
        // 点标签区空白（标签尾部）也能聚焦输入框并弹出输入法
        if (tailTagsScroll != null) {
            tailTagsScroll.setOnClickListener(v -> {
                if (etBillCode != null) {
                    etBillCode.requestFocus();
                    try {
                        android.view.inputmethod.InputMethodManager imm =
                                (android.view.inputmethod.InputMethodManager) requireContext()
                                        .getSystemService(Context.INPUT_METHOD_SERVICE);
                        if (imm != null) imm.showSoftInput(etBillCode, 0);
                    } catch (Exception ignore) {}
                }
            });
        }
        btnQuery = view.findViewById(R.id.btn_query);
        btnClear = view.findViewById(R.id.btn_clear);
        btnTypePhone = view.findViewById(R.id.btn_type_phone);
        btnTypePickup = view.findViewById(R.id.btn_type_pickup);
        btnTypeBill = view.findViewById(R.id.btn_type_bill);
        btnVoice = view.findViewById(R.id.btn_voice);
        switchShowDelivered = view.findViewById(R.id.switch_show_delivered);
        switchGridView = view.findViewById(R.id.switch_grid_view);
        tvResultCount = view.findViewById(R.id.tv_result_count);
        tvResultTimeout = view.findViewById(R.id.tv_result_timeout);
        resultCountFlow = view.findViewById(R.id.result_count_flow);
        resultCountMarquee = view.findViewById(R.id.result_count_marquee);
        if (resultCountFlow != null) {
            // 初始/未查询时不点亮跑马灯（有超时件时才由 updateResultCount 点亮）
            resultCountFlow.setFlowEnabled(false);
        }
        resultCountBox = view.findViewById(R.id.result_count_box);
        if (resultCountBox != null) {
            // 尚未查询时整个结果数框（前缀+超时出库文字+跑马灯）一起隐藏，查询完成后再显示
            resultCountBox.setVisibility(View.INVISIBLE);
        }
        resultsContainer = view.findViewById(R.id.results_container);
        historyPanel = view.findViewById(R.id.history_panel);
        historyPanelHelper = new QueryHistoryPanel(requireContext(), historyPanel, new QueryHistoryPanel.Host() {
            @Override public EditText input() { return etBillCode; }
            @Override public View root() { return getView(); }
            @Override public void setSearchType(String t) { QueryFragment.this.setSearchType(t); }
            @Override public void runQuery() { performQuery(true); }
            @Override public void toast(String msg) { safeToast(msg); }
        });
        // 网格行通过负 margin 向外贴边，行宽会超出本容器边界；
        // 关闭子视图裁剪，避免最左/最右卡片边缘（含边框、圆角）被裁掉
        resultsContainer.setClipChildren(false);
        resultsContainer.setClipToPadding(false);
        progressBar = view.findViewById(R.id.progress_bar);
        tvNoResults = view.findViewById(R.id.tv_no_results);
        loadingMask = view.findViewById(R.id.loading_mask);

        // 统一滚动监听：懒加载 + 历史面板跟随输入框重定位
        // （不再由 attach/detach 反复挂/卸，避免滚动后历史面板脱离输入框）
        if (scrollView != null) {
            scrollView.setOnScrollChangeListener((v, sx, sy, osx, osy) -> {
                if (!isViewReady) return;
                try {
                    if (historyPanelHelper != null) historyPanelHelper.repositionToInput();
                } catch (Throwable ignore) {}
                if (currentScrollListener != null) currentScrollListener.run();
            });
        }

        // 自动刷新指示器初始状态：暗色静止（圆环样式，空闲为静态暗环）
        // 自动刷新控制器：调度 + 指示器（空闲暗色静止，执行时变绿转动）
        autoRefreshController = new AutoRefreshController();
        final TextView autoRefreshLabel = view.findViewById(R.id.auto_refresh_label);
        final View autoRefreshIndicator = view.findViewById(R.id.auto_refresh_indicator);
        autoRefreshController.attach(requireContext(), new AutoRefreshController.Host() {
            @Override public boolean isViewReady() { return QueryFragment.this.isViewReady; }
            @Override public boolean isUserTouching() { return QueryFragment.this.isUserTouching; }
            @Override public int getIntervalSeconds() { return QueryFragment.this.getAutoRefreshSeconds(); }
            @Override public void onTick() { QueryFragment.this.performAutoRefreshTick(); }
            @Override public void onActiveChanged(boolean active) {
                if (switchGridView != null) switchGridView.setEnabled(!active);
            }
        }, autoRefreshIndicator, autoRefreshLabel);
        // 点击"自动刷新中......"：暂停自动刷新，再次点击恢复
        View.OnClickListener autoRefreshToggle = v -> {
            if (autoRefreshController == null) return;
            boolean nowPaused = autoRefreshController.togglePause();
            try {
                LogRecorder.info(requireContext(), "AutoRefresh", nowPaused ? "暂停自动刷新" : "恢复自动刷新", "点击顶部指示器切换");
            } catch (Exception ignore) {}
            Toast.makeText(requireContext(), nowPaused ? "自动刷新已暂停" : "已恢复自动刷新", Toast.LENGTH_SHORT).show();
        };
        autoRefreshLabel.setOnClickListener(autoRefreshToggle);
        autoRefreshIndicator.setOnClickListener(autoRefreshToggle);

        apiService = new ApiService(requireContext());
        syncClient = new SyncClient(apiService);
        directApiClient = new DirectApiClient(requireContext());
        imageUrlResolver = new ImageUrlResolver();
        imageUrlResolver.attach(directApiClient, apiService);
        imageUrlResolver.setUrlLoadedListener((billCode, url) -> {
            // 解析成功：补充到预览列表（供跨卡片翻页）
            synchronized (allImageUrls) {
                if (!allImageUrls.contains(url)) {
                    allImageUrls.add(url);
                    allTrackingNos.add(billCode);
                }
            }
        });
        packageCardFactory = new PackageCardFactory(requireContext(), new PackageCardFactory.Host() {
            @Override public boolean isTimeoutPackage(JSONObject item) { return QueryFragment.this.isTimeoutPackage(item); }
            @Override public String loadCardImage(ImageView iv, String trackingNumber, String imageUrl, String rawImgPath) {
                return QueryFragment.this.loadCardImage(iv, trackingNumber, imageUrl, rawImgPath);
            }
            @Override public void prepareComparePhoto(String trackingNumber, JSONObject item, String rawImgPath) {
                QueryFragment.this.prepareComparePhoto(trackingNumber, item, rawImgPath);
            }
            @Override public void showImagePreview(ImageView iv) { showImagePreviewForCard(iv); }
            @Override public void showTrajectory(String trackingNumber, String expressCompanyCode) {
                try {
                    TrajectoryDialog.show(requireContext(), directApiClient,
                            apiService != null ? apiService.getOkHttpClient() : null,
                            trackingNumber, expressCompanyCode);
                } catch (Throwable ignore) {}
            }
            @Override public void onPicOutboundClick(String billCode, JSONObject item) {
                QueryFragment.this.onPicOutboundClick(billCode, item);
            }
            @Override public boolean isPicOutboundEnabled() {
                try {
                    return requireContext().getSharedPreferences("chajianzhushou_prefs", Context.MODE_PRIVATE)
                            .getBoolean(SettingsStore.KEY_PIC_OUTBOUND_ENABLED, false);
                } catch (Exception e) {
                    return false;
                }
            }
            @Override public void toast(String msg) { safeToast(msg); }
        });

        ttsHelper = TtsHelper.getInstance();
        ttsHelper.init(requireContext());
        isViewReady = true;

        // Load grid view preference - default to grid view ON (竖向排列=网格模式默认开启)
        SharedPreferences prefs = requireContext().getSharedPreferences("chajianzhushou_prefs", Context.MODE_PRIVATE);
        isGridView = prefs.getBoolean(PREFS_GRID, true);
        if (switchGridView != null) switchGridView.setChecked(isGridView);

        // Server sync state
        serverConnectEnabled = prefs.getBoolean(SettingsStore.KEY_SERVER_CONNECT, false);
        syncQueryEnabled = prefs.getBoolean(SettingsStore.KEY_SYNC_QUERY, true);

        // Voice button state: disabled when ASR is off
        boolean asrEnabled = prefs.getBoolean(SettingsStore.KEY_ASR_ENABLED, false);
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
        // 初始化时同步一次输入框提示文字（与默认查询类型一致）
        setSearchType(searchType);
        updateTypeButtons();

        // Clear button
        btnClear.setOnClickListener(v -> {
            if (multiTailEnabled) {
                tailTags.clear();
                currentTailTag = null;
                renderTailTags();
                stopAutoRefresh();
            }
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
                // 连续输入模式：每输满4位数字自动转为标签，并清空输入框继续下一个
                if (isTailModeUsable() && s != null) {
                    String digits = s.toString().replaceAll("\\D", "");
                    int len = digits.length();
                    if (len >= 4) {
                        // 支持逐位输入与粘贴/扫码枪连续输入：每 4 位切一个标签，不足 4 位保留继续输入
                        int consumed = (len / 4) * 4;
                        for (int i = 0; i < consumed; i += 4) {
                            addTailTag(digits.substring(i, i + 4));
                        }
                        etBillCode.setText(digits.substring(consumed));
                    }
                }
            }
        });

        // Query button
        btnQuery.setOnClickListener(v -> {
            historyPanelHelper.hide();
            performQuery(true);
        });

        // 输入框聚焦：展开最近查询记录；失焦：延迟收起（给点击记录留时间）
        etBillCode.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                historyPanelHelper.render();
            } else {
                mainHandler.postDelayed(() -> {
                    if (historyPanel != null && !etBillCode.hasFocus()) {
                        historyPanelHelper.hide();
                    }
                }, 200);
            }
        });

        // 点击输入框（即使已保持焦点）也重新展开查询历史——修复"第二次点击不显示"：
        // 按返回键收起键盘时输入框不丢焦点，第二次点击不会触发 onFocusChange
        etBillCode.setOnTouchListener((v, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_UP) {
                historyPanelHelper.render();
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
                    historyPanelHelper.hide();
                } else if (!keyboardVisible[0] && nowVisible
                        && etBillCode != null && etBillCode.hasFocus()) {
                    // 键盘从隐藏→弹出且输入框有焦点：补一次显示
                    historyPanelHelper.render();
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
        if (!prefs.getBoolean(SettingsStore.KEY_ASR_ENABLED, false)) {
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
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        // Fragment 用 show/hide 切换时 onResume 不会再次触发；
        // 从设置页切回本页时在此同步开关状态（含"输入框连续输入"）
        if (!hidden && isViewReady) {
            refreshSettingsFromPrefs();
        }
        // 从设置页切回查件页：仅当"手动每行卡片数"等网格配置真正变化时才重建列表，
        // 避免每次切换都全量重建导致列表图片全部重新加载
        if (!hidden && isViewReady && isGridView
                && currentPackages != null && currentPackages.size() > 0) {
            mainHandler.post(() -> {
                if (!isViewReady) return;
                String key = gridRenderKey();
                if (key.equals(lastGridRenderKey)) return;
                lastGridRenderKey = key;
                boolean wasAuto = isAutoRefresh;
                isAutoRefresh = false;
                renderList();
                isAutoRefresh = wasAuto;
            });
        }
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
        updateVoiceButtonState(prefs.getBoolean(SettingsStore.KEY_ASR_ENABLED, false));
            boolean multiTail = prefs.getBoolean(SettingsStore.KEY_MULTI_TAIL_ENABLED, false);
            if (multiTail != multiTailEnabled) {
                multiTailEnabled = multiTail;
                applyMultiTailMode();
            }
            boolean wasConnected = serverConnectEnabled;
            serverConnectEnabled = prefs.getBoolean(SettingsStore.KEY_SERVER_CONNECT, false);
            syncQueryEnabled = prefs.getBoolean(SettingsStore.KEY_SYNC_QUERY, true);
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
            // 主题切换等 Activity 重建时保留"刚刚出库"标记，避免排序块丢失
            if (!justOutboundBillCodes.isEmpty()) {
                outState.putString("qb_justOutbound", new JSONArray(justOutboundBillCodes).toString());
            }
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
            String savedJustOutbound = savedInstanceState.getString("qb_justOutbound", "");
            if (savedJustOutbound != null && savedJustOutbound.length() > 0) {
                JSONArray arr = new JSONArray(savedJustOutbound);
                for (int i = 0; i < arr.length(); i++) {
                    String bc = arr.optString(i, "");
                    if (bc.length() > 0) justOutboundBillCodes.add(bc);
                }
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
                    hasQueried = true;
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
        historyPanelHelper = null;

        if (syncClient != null) {
            try { syncClient.disconnect(); } catch (Exception ignore) {}
            syncClient = null;
        }

        stopAutoRefresh();
        if (packageCardFactory != null) {
        packageCardFactory.stopAllBlink();
        packageCardFactory = null;
        tailTagsContainer = null;
        tailTagsScroll = null;
        }

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
        tvResultTimeout = null;
        resultCountFlow = null;
        resultCountMarquee = null;
        resultCountBox = null;
        resultsContainer = null;
        historyPanel = null;
        progressBar = null;
        tvNoResults = null;
        loadingMask = null;
        apiService = null;
        if (autoRefreshController != null) autoRefreshController.release();
        autoRefreshController = null;

        super.onDestroyView();
    }

    // ===== Helpers =====

    // ===== 拍照出库（仅待出库包裹卡片上的"拍照出库"按钮触发） =====

    // ===== 输入框连续输入（多手机尾号标签） =====

    /** 连续输入是否可用：开关开启 且 查询类型为手机尾号 */
    private boolean isTailModeUsable() {
        return multiTailEnabled && "phoneTail".equals(searchType);
    }

    /** 渲染所有尾号标签（当前查询标签高亮） */
    private void renderTailTags() {
        if (tailTagsContainer == null || !isViewReady) return;
        // 没有标签时隐藏标签区，让输入框占满整行（hint 靠左、整行可点）
        if (tailTagsScroll != null) {
            tailTagsScroll.setVisibility((isTailModeUsable() && !tailTags.isEmpty()) ? View.VISIBLE : View.GONE);
        }
        tailTagsContainer.removeAllViews();
        for (final String tag : tailTags) {
            TextView chip = new TextView(requireContext());
            chip.setText(tag);
            chip.setTextSize(13f);
            chip.setTypeface(Typeface.DEFAULT_BOLD);
            boolean current = tag.equals(currentTailTag);
            chip.setTextColor(getResources().getColor(current ? R.color.accent : R.color.ink2, requireContext().getTheme()));
            chip.setBackgroundResource(current ? R.drawable.bg_status_pending : R.drawable.bg_btn_back);
            chip.setPadding(dp(8), dp(3), dp(8), dp(3));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(6);
            chip.setLayoutParams(lp);
            // 点击：切换为当前查询标签并立即查询（位置不变，只改变查询优先级）
            chip.setOnClickListener(v -> switchCurrentTailTag(tag));
            // 长按：删除该标签
            chip.setOnLongClickListener(v -> {
                removeTailTag(tag);
                return true;
            });
            tailTagsContainer.addView(chip);
        }
        if (tailTagsScroll != null) {
            tailTagsScroll.post(() -> {
                if (tailTagsScroll != null) tailTagsScroll.fullScroll(View.FOCUS_RIGHT);
            });
        }
    }

    /** 添加尾号标签（满4位数字自动调用） */
    private void addTailTag(String tail) {
        if (tail == null || tail.length() != 4 || !tail.matches("\\d{4}")) return;
        if (tailTags.contains(tail)) return;
        tailTags.add(tail);
        if (currentTailTag == null) currentTailTag = tail;
        renderTailTags();
    }

    /** 删除尾号标签（长按）；若删除的是当前查询标签且有剩余，则自动查询下一个 */
    private void removeTailTag(String tail) {
        boolean wasCurrent = tail.equals(currentTailTag);
        tailTags.remove(tail);
        if (wasCurrent) {
            // 先更新当前标签，再渲染，保证下一个标签正确高亮
            currentTailTag = tailTags.isEmpty() ? null : tailTags.get(0);
        }
        renderTailTags();
        if (wasCurrent && currentTailTag != null && isViewReady) {
            performQuery(false, false, currentTailTag);
        }
        if (tailTags.isEmpty()) {
            stopAutoRefresh();
        }
    }

    /** 点击标签：立即切换当前查询标签并查询（标签位置不变，仅改变查询优先级） */
    private void switchCurrentTailTag(String tail) {
        if (!tailTags.contains(tail)) return;
        // 点击切换：自动删除切换前正在查询的那个标签
        if (currentTailTag != null && !currentTailTag.equals(tail)) {
            tailTags.remove(currentTailTag);
        }
        currentTailTag = tail;
        renderTailTags();
        if (isViewReady) performQuery(false, false, tail);
    }

    /** 根据开关状态显示/隐藏标签容器；关闭时清空标签 */
    private void applyMultiTailMode() {
        if (!isTailModeUsable()) {
            tailTags.clear();
            currentTailTag = null;
        }
        // 可见性由 renderTailTags 统一决定（无标签时隐藏标签区，输入框占满整行）
        renderTailTags();
    }

    private int dp(int v) {
        return (int) (getResources().getDisplayMetrics().density * v + 0.5f);
    }

    /** 点击"拍照出库"：打开系统相机拍照 */
    private void onPicOutboundClick(String billCode, JSONObject item) {
        if (billCode == null || billCode.length() == 0) {
            safeToast("缺少单号");
        }
        try {
            java.io.File dir = new java.io.File(requireContext().getCacheDir(), "photos");
            if (!dir.exists()) dir.mkdirs();
            java.io.File photo = new java.io.File(dir, "out_" + System.currentTimeMillis() + ".jpg");
            Uri uri = FileProvider.getUriForFile(requireContext(),
                    requireContext().getPackageName() + ".fileprovider", photo);
            picOutboundPendingBillCode = billCode;
            picOutboundPendingItem = item;
            picOutboundLauncher.launch(uri);
        } catch (Exception e) {
            safeToast("无法启动相机: " + e.getMessage());
        }
    }

    /** 拍照成功：压缩照片 → 上传（失败不阻断）→ 出库 → 刷新列表 */
    private void onPhotoCaptured() {
        try {
            java.io.File dir = new java.io.File(requireContext().getCacheDir(), "photos");
            java.io.File[] files = dir.listFiles((d, n) -> n.endsWith(".jpg"));
            java.io.File photo = null;
            if (files != null) {
                for (java.io.File f : files) {
                    if (photo == null || f.lastModified() > photo.lastModified()) photo = f;
                }
            }
            if (photo == null || !photo.exists()) {
                safeToast("照片读取失败");
                return;
            }
            final String base64 = compressPhotoToBase64(photo);
            final String bill = picOutboundPendingBillCode;
            final JSONObject pkg = picOutboundPendingItem;
            if (bill == null) {
                safeToast("缺少单号");
                return;
            }
            safeToast("照片已就绪，正在出库...");
            // 上传照片（失败不阻断出库，与官方兜底一致）
            directApiClient.uploadOutboundPic(bill, resolveExpressCompanyCode(pkg), base64,
                    new DirectApiClient.OutboundCallback() {
                        @Override public void onSuccess(JSONObject response) {
                            doPicOutbound(bill, pkg);
                        }
                        @Override public void onError(String error) {
                            Log.w(TAG, "照片上传失败(继续出库): " + error);
                            doPicOutbound(bill, pkg);
                        }
                    });
        } catch (Exception e) {
            safeToast("照片处理失败: " + e.getMessage());
        }
    }

    /** 执行出库并刷新列表 */
    private void doPicOutbound(final String billCode, final JSONObject item) {
        String receiveMan = firstNonEmpty(
                item == null ? "" : item.optString("recipientName", ""),
                item == null ? "" : item.optString("receiveMan", ""),
                item == null ? "" : item.optString("receiver", ""));
        // 站点固定坐标（与超时件出库一致）
        String lation = "116.236085,39.084864";
        directApiClient.outboundPackage(billCode, receiveMan, lation, "",
                new DirectApiClient.OutboundCallback() {
                    @Override public void onSuccess(JSONObject response) {
                        if (!isViewReady) return;
                        safeToast("出库成功: " + billCode);
                        try { LogRecorder.info(requireContext(), "QUERY", "拍照出库", "billCode=" + billCode); } catch (Exception ignore) {}
                        performQuery(true);
                    }
                    @Override public void onError(String error) {
                        if (!isViewReady) return;
                        UiErrorHandler.handle(requireContext(), error);
                        safeToast("出库失败: " + error);
                    }
                });
    }

    /** 压缩照片并转 base64（最长边 1280，JPEG 80%） */
    private String compressPhotoToBase64(java.io.File file) throws Exception {
        final int MAX_SIDE = 1280;
        android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        int sample = 1;
        int maxSide = Math.max(bounds.outWidth, bounds.outHeight);
        while (maxSide / (sample * 2) >= MAX_SIDE) sample *= 2;
        android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
        opts.inSampleSize = sample;
        android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
        if (bmp == null) throw new Exception("图片解码失败");
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, bos);
        bmp.recycle();
        return android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP);
    }

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
        // 输入框提示文字跟随查询类型变化：手机尾号 / 取件码 / 运单号
        if (etBillCode != null) {
            if ("pickupCode".equals(searchType)) {
                etBillCode.setHint("请输入取件码");
            } else if ("billCode".equals(searchType)) {
                etBillCode.setHint("请输入运单号");
            } else {
                etBillCode.setHint("请输入手机尾号");
            }
        }
        // 注意：切换类型不停止自动刷新循环，下一轮自动刷新会按新类型继续查询
        updateTypeButtons();
        // 连续输入只支持手机尾号：切换类型后刷新标签区可用性
        applyMultiTailMode();
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
            // 先检查设备上是否有可处理语音识别的应用（未安装/停用识别服务时 resolveActivity 返回 null）
            if (intent.resolveActivity(requireContext().getPackageManager()) == null) {
                safeToast("当前设备未安装语音识别服务，无法使用语音查询");
                try {
                    LogRecorder.warn(requireContext(), "ASR", "语音识别不可用",
                            "设备上无处理 RECOGNIZE_SPEECH 的应用，请安装/启用语音识别服务");
                } catch (Exception ignore) {}
                return;
            }
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
        if (autoRefreshController != null) autoRefreshController.setActive(active);
    }

    private int getAutoRefreshSeconds() {
        try {
            SharedPreferences prefs = requireContext().getSharedPreferences("chajianzhushou_prefs", Context.MODE_PRIVATE);
            return prefs.getInt(KEY_AUTO_REFRESH, 0);
        } catch (Exception e) {
            return 0;
        }
    }

    /** 读取设置"自动刷新次数上限"（3-30，默认10）；间隔关闭或未配置时不受限 */
    private int getAutoRefreshMaxCount() {
        try {
            SharedPreferences prefs = requireContext().getSharedPreferences("chajianzhushou_prefs", Context.MODE_PRIVATE);
            int max = prefs.getInt(KEY_AUTO_REFRESH_MAX, 10);
            if (max < 3) max = 10;
            if (max > 30) max = 30;
            return max;
        } catch (Exception e) {
            return 10;
        }
    }
    private void startAutoRefreshLoop() {
        if (autoRefreshController == null) return;
        int maxCount = getAutoRefreshMaxCount();
        if (autoRefreshTickCount >= maxCount) {
            // 达到刷新上限：自动暂停，不再续排；再次点击指示器可恢复
            if (!autoRefreshController.isPaused()) {
                autoRefreshController.pause();
                try {
                    Toast.makeText(requireContext(), "已达自动刷新上限（" + maxCount + "次），已暂停", Toast.LENGTH_SHORT).show();
                } catch (Exception ignore) {}
                try {
                    LogRecorder.info(requireContext(), "AutoRefresh", "达到刷新上限", "已自动暂停 count=" + autoRefreshTickCount + " max=" + maxCount);
                } catch (Exception ignore) {}
            }
        }
        if (autoRefreshController != null) autoRefreshController.startLoop();
    }

    private void stopAutoRefresh() {
        if (autoRefreshController != null) autoRefreshController.stop();
    }

    /** 自动刷新一轮：始终沿用最后一次查询条件（手动改输入框不生效，需点"查询"才切换） */
    private void performAutoRefreshTick() {
        if (!isViewReady) return;
        if (etBillCode != null) {
            String q = lastQueriedBillCode;
            if (q.length() > 0) {
                if (performQuery(false, true, q)) {
                    // 仅真正发起查询才计入自动刷新轮次（被并发/节流拒绝的 tick 不计数）
                    autoRefreshTickCount++;
                }
                return;
            }
        }
        stopAutoRefresh();
    }

    // ===== Query =====

    private boolean performQuery(boolean syncToPc) {
        return performQuery(syncToPc, false, null, true);
    }

    private boolean performQuery(boolean syncToPc, boolean isAuto) {
        return performQuery(syncToPc, isAuto, null, true);
    }

    private boolean performQuery(boolean syncToPc, boolean isAuto, String explicitValue) {
        return performQuery(syncToPc, isAuto, explicitValue, true);
    }

    /**
     * @param explicitValue 显式查询值；为 null 时读取输入框内容。
     *                     输入框清空后自动刷新会传入最后一次查询值，保证列表不消失、刷新继续。
     * @param recordHistory 是否计入"最近查询"历史（语音识别、自动刷新不计入）
     */
    private boolean performQuery(boolean syncToPc, boolean isAuto, String explicitValue, boolean recordHistory) {
        if (!isViewReady || etBillCode == null) return false;
        long now = System.currentTimeMillis();
        if (isQuerying) return false;
        if (now - lastQueryAt < 400) return false;
        lastQueryAt = now;
        isQuerying = true;
        __tReqStart = now;
        __tRespArrived = 0;
        __queryMode = "";

        // 连续输入模式：查询当前标签尾号（点击标签/自动切换会传入 explicitValue）
        String billCode;
        if (isTailModeUsable() && !tailTags.isEmpty()) {
            if (currentTailTag == null) currentTailTag = tailTags.get(0);
            billCode = (explicitValue != null) ? explicitValue : currentTailTag;
        } else {
            billCode = (explicitValue != null) ? explicitValue : etBillCode.getText().toString().trim();
        }
        if (billCode.isEmpty()) {
            isQuerying = false;
            if (!isAuto) safeToast("请输入查询内容");
            return false;
        }
        // 记住本次实际查询条件：清空输入后自动刷新仍按此继续
        lastQueriedBillCode = billCode;
        // 自动刷新固定使用"当前列表对应的查询类型"：手动切换类型后，自动刷新不按新类型查询
        String effectiveType = isAuto ? lastQueriedType : searchType;
        if (!isAuto) {
            lastQueriedType = effectiveType;
            // 手动查询：清空"刚刚出库"标记，重新按服务器返回构建分块列表
            justOutboundBillCodes.clear();
            // 手动查询：重置自动刷新轮次计数
            autoRefreshTickCount = 0;
            // 手动查询：解除自动刷新暂停（上限暂停后，再次查询应恢复自动刷新能力）
            if (autoRefreshController != null) autoRefreshController.unpause();
            // 手动查询：清空图片 URL 解析缓存，重新解析签名 URL
            // （否则"一键清理缓存"后仍会复用内存里已过期的 URL，图片显示过期）
            if (imageUrlResolver != null) imageUrlResolver.clearCache();
        }
        // 记录查询历史（输入框聚焦时展示"最近查询"）；自动刷新/语音识别不计入
        if (recordHistory && !isAuto) {
            historyPanelHelper.record(billCode, effectiveType);
        }

        boolean sd = (switchShowDelivered != null) ? switchShowDelivered.isChecked() : showDelivered;

        Log.d(TAG, "执行查询: billCode=" + billCode + " type=" + effectiveType + " showDelivered=" + sd + " isAuto=" + isAuto);
        try {
            LogRecorder.info(requireContext(), "Query", "执行查询",
                    "billCode=" + billCode + " type=" + effectiveType + " showDelivered=" + sd + " isAuto=" + isAuto);
        } catch (Exception ignore) {}

        if (syncToPc && serverConnectEnabled && syncQueryEnabled && syncClient != null) {
            syncClient.sendQueryTrigger(billCode, effectiveType, sd);
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
            body.put("type", effectiveType);
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
                        UiErrorHandler.handle(requireContext(), error);
                        if (!isAuto) safeToast("查询失败: " + error);
                        // 查询失败时如果设置了自动刷新间隔且有输入，保持循环继续尝试
                        rescheduleAutoRefreshOnErrorOrEmpty();
                    }
                });
            } else {
                // Direct mode: call ZTO API directly
                __queryMode = "DIRECT";
                Threads.io().execute(() -> {
                    try {
                        JSONObject response = directApiClient.queryPackages(billCode, effectiveType, isAuto);
                        __tRespArrived = System.currentTimeMillis();
                        if (!isViewReady) return;
                        mainHandler.post(() -> handleQueryResponse(response, sd, isAuto));
                    } catch (Exception e) {
                        if (!isViewReady) return;
                        mainHandler.post(() -> {
                            isQuerying = false;
                            showLoading(false);
                            setAutoRefreshIndicatorActive(false);
                            UiErrorHandler.handle(requireContext(), e.getMessage());
                            if (!isAuto) safeToast("查询失败: " + e.getMessage());
                            // 查询失败时如果设置了自动刷新间隔且有输入，保持循环继续尝试
                            rescheduleAutoRefreshOnErrorOrEmpty();
                        });
                    }
                });
            }
            return true; // 已成功发起查询（异步结果由回调处理）
        } catch (Exception e) {
            isQuerying = false;
            showLoading(false);
            setAutoRefreshIndicatorActive(false);
            UiErrorHandler.handle(requireContext(), e.getMessage());
            if (!isAuto) safeToast("查询失败: " + e.getMessage());
            // 查询失败时如果设置了自动刷新间隔且有输入，保持循环继续尝试
            rescheduleAutoRefreshOnErrorOrEmpty();
            return false;
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
            UiErrorHandler.handle(requireContext(), "查询失败: 响应为空");
            if (!isAuto) safeToast("查询失败: 响应为空");
            rescheduleAutoRefreshOnErrorOrEmpty();
            return;
        }

        boolean ok = response.optBoolean("ok", false);
        if (!ok) {
            Log.w(TAG, "查询失败: " + response.optString("error", "未知错误"));
            try { LogRecorder.warn(requireContext(), "Query", "查询失败", response.optString("error", "未知错误")); } catch (Exception ignore) {}
            UiErrorHandler.handle(requireContext(), response.optString("error", "未知错误"));
            if (!isAuto) safeToast("查询失败: " + response.optString("error", "未知错误"));
            rescheduleAutoRefreshOnErrorOrEmpty();
            return;
        }
        // 查询成功过：结果数框（前缀+超时出库文字+跑马灯）随列表一同显示
        hasQueried = true;

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

        // 本次查询条件：仅当与当前列表同条件时才做"保留已出库"合并；条件变了（换了查询值）直接整表替换，不混列表
        final String currentQueryKey = lastQueriedBillCode + "|" + lastQueriedType;

        java.util.Set<String> autoFreshBillCodes = null; // pendingOnly 响应基线，供补查"消失的待取件"用
        List<String> gonePendingBillCodes = new ArrayList<>(); // 自动刷新+关闭"显示已出库"：消失的待取件（刚出库）→ 颗粒化渐隐
        boolean deferRender = false; // 需要先播完颗粒消失动画再渲染
        if (isAuto && oldPackages != null && oldPackages.size() > 0 && currentQueryKey.equals(listQueryKey)) {
            java.util.Set<String> newBillCodes = new java.util.HashSet<>();
            for (JSONObject pkg : newPackages) {
                String bc = pkgBillCode(pkg);
                if (bc.length() > 0) newBillCodes.add(bc);
            }
            // 从旧列表中补充已出库包裹（不在新待取件列表中）
            for (JSONObject oldPkg : oldPackages) {
                if (!"delivered".equals(oldPkg.optString("status", ""))) continue;
                String bc = pkgBillCode(oldPkg);
                if (bc.length() > 0 && !newBillCodes.contains(bc)) {
                    currentPackages.add(oldPkg);
                }
            }
            if (sd) {
                // 显示已出库开启：旧"待取件"在响应中消失（多半刚出库）时，先保留原卡片避免闪烁消失，
                // 立即标记为"刚刚出库"（进入"待取件→超时件"之间的专用块），稍后按单号补查最新状态、原位更新为已出库。
                autoFreshBillCodes = newBillCodes;
                for (JSONObject oldPkg : oldPackages) {
                    if ("delivered".equals(oldPkg.optString("status", ""))) continue;
                    String bc = pkgBillCode(oldPkg);
                    if (bc.length() > 0 && !newBillCodes.contains(bc)) {
                        currentPackages.add(oldPkg);
                        justOutboundBillCodes.add(bc);
                    }
                }
            } else {
                // 显示已出库关闭：消失的待取件（多半刚出库）不保留，播放"颗粒化渐隐"消失动画
                for (JSONObject oldPkg : oldPackages) {
                    if ("delivered".equals(oldPkg.optString("status", ""))) continue;
                    String bc = pkgBillCode(oldPkg);
                    if (bc.length() > 0 && !newBillCodes.contains(bc)) {
                        gonePendingBillCodes.add(bc);
                    }
                }
                deferRender = !gonePendingBillCodes.isEmpty();
            }
        }
        // 四块结构重排：待取件 → 刚刚出库 → 超时件 → 已出库（只重排"刚刚出库"块，其余块保持原相对顺序，最小移动）
        if (isAuto) {
            reorderPackagesForDisplay();
        }
        // 自动刷新 + 显示已出库：旧列表中的"待取件"在 pendingOnly 响应中消失 → 多半刚出库。
        // 按单号补查最新状态并用新数据原位替换，保证卡片不消失，状态/边框/图片实时更新为已出库。
        if (isAuto && sd && autoFreshBillCodes != null) {
            refreshMissingPendingAfterAutoRefresh(oldPackages, autoFreshBillCodes);
        }
        // 记录当前列表对应的查询条件，供下一轮自动刷新合并时校验
        listQueryKey = currentQueryKey;
        long _parseFilterCost = System.currentTimeMillis() - _tParseStart;

        // TTS
        int pendingCount = 0;
        for (JSONObject pkg : currentPackages) {
            // 口径：已标记"刚刚出库"的卡片（补查确认前的旧待取件数据）不再计入待取，
            // 避免补查完成前 TTS/自动刷新停止判断多算 1 个
            if ("pending".equals(pkg.optString("status", ""))
                    && !justOutboundBillCodes.contains(pkgBillCode(pkg))) pendingCount++;
        }

        Log.d(TAG, "查询完成: " + currentPackages.size() + " 条结果, pending=" + pendingCount);
        try {
            LogRecorder.info(requireContext(), "Query", "查询完成",
                    "总数=" + currentPackages.size() + " pending=" + pendingCount);
        } catch (Exception ignore) {}

        if (pendingCount != lastPendingCount || !isAutoRefresh) {
            if (pendingCount > 0) {
                String ttsText = isAutoRefresh
                        ? "剩余" + pendingCount + "个待取包裹"
                        : "共" + pendingCount + "个待取包裹";
                try {
                    ttsHelper.speak(requireContext(), ttsText, new TtsHelper.TtsCallback() {
                        @Override
                        public void onDone() {}

                        @Override
                        public void onError(String error) {}
                    });
                } catch (Exception ignore) {}
            } else {
                // 无待取包裹：按是否存在"标注的超时件"播报不同内置提示音
                // 先作废上一轮 TTS 的迟到响应（否则 MiMo 音频晚到会与内置提示音叠加/串台）
                try { if (ttsHelper != null) ttsHelper.stop(); } catch (Exception ignore) {}
                int timeoutMarkedCount = 0;
                for (JSONObject pkg : currentPackages) {
                    if (isTimeoutPackage(pkg)) timeoutMarkedCount++;
                }
                try {
                    if (timeoutMarkedCount > 0) {
                        AudioPlayerHelper.play(requireContext(), R.raw.no_pending_with_timeout);
                    } else {
                        AudioPlayerHelper.play(requireContext(), R.raw.no_pending);
                    }
                } catch (Exception ignore) {}
            }
            lastPendingCount = pendingCount;
        }

        // Auto-refresh: 根据设置的间隔自动刷新查询，直到没有待取件(pending)包裹为止
        int interval = getAutoRefreshSeconds();
        if (pendingCount > 0) {
            if (interval > 0) {
                startAutoRefreshLoop();
            }
        } else {
            stopAutoRefresh();
            if (isTailModeUsable() && !tailTags.isEmpty()) {
                // 连续输入模式：当前尾号已无待取件 → 停留，不自动切换下一个；
                // 用户手动点击其他标签（chip）才会切换查询（switchCurrentTailTag）。
                // 标签保留，便于用户查看/再次点击。
            } else {
                // 自动刷新后没有待取件了：自动清空输入框。
                // 仅自动刷新场景生效，且输入框内容仍是本次查询值时才清（避免打断用户正在输入的新内容）。
                if (isAuto && etBillCode != null) {
                    String cur = etBillCode.getText().toString().trim();
                    if (cur.length() > 0 && cur.equals(lastQueriedBillCode)) {
                        etBillCode.setText("");
                    }
                }
            }
        }

        long _tRenderStart = System.currentTimeMillis();
        if (deferRender) {
            playDissolveAndRender(gonePendingBillCodes);
        } else {
            renderList();
        }
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
                String bc = pkgBillCode(oldPkg);
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
                        mainHandler.post(() -> {
                            JSONObject fresh = extractFirstPackageFromQueryResponse(response);
                            if (fresh == null) {
                                clearJustOutboundMark(billCode);
                            } else {
                                applyFreshPackage(billCode, oldPkg, fresh);
                            }
                        });
                    }
                    @Override
                    public void onError(String error) {
                        if (!isViewReady) return;
                        mainHandler.post(() -> clearJustOutboundMark(billCode));
                    }
                });
            } else if (directApiClient != null) {
                Threads.io().execute(() -> {
                    JSONObject fresh = null;
                    try {
                        fresh = extractFirstPackageFromQueryResponse(directApiClient.queryPackages(billCode, "billCode"));
                    } catch (Throwable ignore) {}
                    if (!isViewReady) return;
                    final JSONObject finalFresh = fresh;
                    try {
                        mainHandler.post(() -> {
                            if (finalFresh == null) {
                                clearJustOutboundMark(billCode);
                            } else {
                                applyFreshPackage(billCode, oldPkg, finalFresh);
                            }
                        });
                    } catch (Throwable ignore) {}
                });
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

    /** 取消某单号的"刚刚出库"标记（补查失败/无结果/仍为待取件时），并重排+重绘 */
    private void clearJustOutboundMark(String billCode) {
        if (billCode == null || billCode.length() == 0) return;
        if (justOutboundBillCodes.remove(billCode)) {
            reorderPackagesForDisplay();
            boolean wasAuto = isAutoRefresh;
            isAutoRefresh = true;
            renderList();
            isAutoRefresh = wasAuto;
        }
    }

    /** 用补查到的最新包裹数据替换列表中的旧条目（已出库则显示新状态/新图片），并触发差分重绘。 */
    private void applyFreshPackage(String billCode, JSONObject oldPkg, JSONObject fresh) {
        if (!isViewReady) return;
        if (fresh == null || fresh.length() == 0) {
            // 补查无结果：取消"刚刚出库"标记，卡片回到待取件块（保持现状，不删除）
            clearJustOutboundMark(billCode);
            return;
        }
        try {
            Log.d(TAG, "补查单号=" + billCode + " 旧状态=" + oldPkg.optString("status", "")
                    + " 新状态=" + fresh.optString("status", "") + " 新图=" + fresh.optString("imageUrl", ""));
            try {
                LogRecorder.info(requireContext(), "Query", "出库状态补查",
                        "billCode=" + billCode + " old=" + oldPkg.optString("status", "")
                                + " new=" + fresh.optString("status", ""));
            } catch (Exception ignore) {}
            int idx = -1;
            for (int i = 0; i < currentPackages.size(); i++) {
                String bc = pkgBillCode(currentPackages.get(i));
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
                // 标记"刚刚出库"：补查确认已出库 → 进入"刚刚出库"块；仍为待取件（数据抖动）→ 取消标记
                if ("delivered".equals(fresh.optString("status", ""))) {
                    justOutboundBillCodes.add(billCode);
                } else {
                    justOutboundBillCodes.remove(billCode);
                }
                reorderPackagesForDisplay();
                // 保持差分渲染模式（避免整批重建造成闪烁/跳动）
                boolean wasAuto = isAutoRefresh;
                isAutoRefresh = true;
                renderList();
                isAutoRefresh = wasAuto;
            }
        } catch (Throwable ignore) {}
    }

    // ===== 四块结构排序（待取件 → 刚刚出库 → 超时件 → 已出库） =====

    /** 取包裹单号（兼容 billCode / trackingNumber / waybillCode 三种字段） */
    private String pkgBillCode(JSONObject pkg) {
        return firstNonEmpty(pkg.optString("billCode", ""),
                pkg.optString("trackingNumber", ""),
                pkg.optString("waybillCode", ""));
    }

    /** 分块序号：0=待取件，1=刚刚出库，2=超时件，3=已出库；"刚刚出库"优先于"超时件"（约定不特殊处理） */
    private int packageDisplayBlock(JSONObject pkg) {
        // 标记优先：合并时"消失的待取件"已标记，立即进入"刚刚出库"块（避免先排到待取件末尾、补查后又跳一次）
        if (justOutboundBillCodes.contains(pkgBillCode(pkg))) return 1;
        String status = pkg.optString("status", "");
        if (!"delivered".equals(status)) return 0;
        if (isTimeoutPackage(pkg)) return 2;
        return 3;
    }

    /** 出库时间毫秒（用于"刚刚出库"块内按时间新→旧排序；解析失败返回 0） */
    private long packageOutboundMillis(JSONObject pkg) {
        String t = firstNonEmpty(pkg.optString("outboundTime", ""),
                pkg.optString("takeDate", ""),
                pkg.optString("deliveryTime", ""),
                pkg.optString("deliveredTime", ""),
                pkg.optString("outTime", ""),
                pkg.optString("outboundAt", ""));
        return PackageCardFactory.parseTimeMillis(t);
    }

    /** 排序用时间：已标记"刚刚出库"但数据仍是待取件（补查未回）的卡片，按"最新"处理，先排块顶 */
    private long packageSortMillis(JSONObject pkg) {
        if (!"delivered".equals(pkg.optString("status", ""))) return Long.MAX_VALUE;
        return packageOutboundMillis(pkg);
    }

    /** 按四块结构重排 currentPackages：只重排"刚刚出库"块（出库时间新→旧），其余块保持原相对顺序（最小移动） */
    private void reorderPackagesForDisplay() {
        if (currentPackages == null || currentPackages.isEmpty()) return;
        List<JSONObject> b0 = new ArrayList<>();
        List<JSONObject> b1 = new ArrayList<>();
        List<JSONObject> b2 = new ArrayList<>();
        List<JSONObject> b3 = new ArrayList<>();
        for (JSONObject p : currentPackages) {
            switch (packageDisplayBlock(p)) {
                case 1: b1.add(p); break;
                case 2: b2.add(p); break;
                case 3: b3.add(p); break;
                default: b0.add(p); break;
            }
        }
        // 刚刚出库块：出库时间新→旧（时间缺失或相同保持原顺序，排序稳定）
        java.util.Collections.sort(b1, new java.util.Comparator<JSONObject>() {
            @Override
            public int compare(JSONObject x, JSONObject y) {
                return Long.compare(packageSortMillis(y), packageSortMillis(x));
            }
        });
        List<JSONObject> merged = new ArrayList<>(currentPackages.size());
        merged.addAll(b0);
        merged.addAll(b1);
        merged.addAll(b2);
        merged.addAll(b3);
        currentPackages = merged;
    }

    // ===== 颗粒化渐隐（自动刷新 + 关闭"显示已出库"时，刚出库卡片消失特效） =====

    /** 按单号查找已渲染的卡片视图（兼容网格模式的行内卡片与列表模式的直接子卡片） */
    private List<View> findCardViewsByBillCodes(List<String> billCodes) {
        List<View> out = new ArrayList<>();
        if (resultsContainer == null || billCodes == null || billCodes.isEmpty()) return out;
        java.util.Set<String> targets = new java.util.HashSet<>(billCodes);
        try {
            for (int i = 0; i < resultsContainer.getChildCount(); i++) {
                View child = resultsContainer.getChildAt(i);
                if (child instanceof LinearLayout && ((LinearLayout) child).getOrientation() == LinearLayout.HORIZONTAL) {
                    LinearLayout row = (LinearLayout) child;
                    for (int j = 0; j < row.getChildCount(); j++) {
                        View card = row.getChildAt(j);
                        Object tag = card.getTag(R.id.btn_query);
                        if (tag != null && targets.contains(tag.toString())) out.add(card);
                    }
                } else {
                    Object tag = child.getTag(R.id.btn_query);
                    if (tag != null && targets.contains(tag.toString())) out.add(child);
                }
            }
        } catch (Throwable ignore) {}
        return out;
    }

    /** 把卡片当前画面截成位图（用于生成颗粒渐隐帧；主线程调用） */
    private android.graphics.Bitmap captureCardBitmap(View card) {
        if (card == null || card.getWidth() <= 0 || card.getHeight() <= 0) return null;
        try {
            android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                    card.getWidth(), card.getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
            card.draw(new android.graphics.Canvas(bmp));
            return bmp;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 完成一张卡片的消失流程；全部完成后执行 finishAll（重建列表） */
    private void finishDissolveOne(final int[] remaining, final Runnable finishAll) {
        synchronized (remaining) {
            remaining[0]--;
            if (remaining[0] <= 0) {
                finishAll.run();
            }
        }
    }

    /** 播放单张卡片的颗粒化渐隐（主线程调用；帧已由后台线程生成） */
    private void startDissolveAnimation(final View card, final List<android.graphics.Bitmap> frames,
                                        final int gen, final int[] remaining, final Runnable finishAll) {
        try {
            if (!isViewReady || gen != renderGeneration || card.getParent() == null
                    || frames == null || frames.isEmpty()) {
                finishDissolveOne(remaining, finishAll);
                return;
            }
            final DissolveView dv = new DissolveView(requireContext());
            ViewGroup.LayoutParams lp = card.getLayoutParams();
            LinearLayout parent = (LinearLayout) card.getParent();
            int idx = parent.indexOfChild(card);
            parent.removeView(card);
            dv.setLayoutParams(lp);
            dv.setFrames(frames);
            parent.addView(dv, Math.max(0, idx));
            android.animation.ValueAnimator anim = android.animation.ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(520);
            anim.setInterpolator(new android.view.animation.LinearInterpolator());
            anim.addUpdateListener(a -> dv.setProgress((Float) a.getAnimatedValue()));
            anim.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator animation) {
                    try {
                        if (dv.getParent() != null) {
                            ((ViewGroup) dv.getParent()).removeView(dv);
                        }
                    } catch (Throwable ignore) {}
                    dv.recycleFrames();
                    finishDissolveOne(remaining, finishAll);
                }
            });
            anim.start();
        } catch (Throwable t) {
            finishDissolveOne(remaining, finishAll);
        }
    }

    /** 自动刷新 + 关闭"显示已出库"：对消失的待取件卡片播放颗粒化渐隐，全部播完后重建列表 */
    private void playDissolveAndRender(List<String> goneBillCodes) {
        if (goneBillCodes == null || goneBillCodes.isEmpty() || !isViewReady) {
            renderList();
            return;
        }
        final List<View> cards = findCardViewsByBillCodes(goneBillCodes);
        final int animCount = Math.min(cards.size(), 6); // 同时最多动画 6 张，其余随 renderList 直接移除
        if (animCount <= 0) {
            renderList();
            return;
        }
        final int gen = renderGeneration;
        final int[] remaining = {animCount};
        final Runnable finishAll = () -> {
            // 只在没有更新的渲染发生时重建列表，并保持差分渲染模式（避免整表重建跳动）
            if (gen == renderGeneration && isViewReady) {
                boolean wasAuto = isAutoRefresh;
                isAutoRefresh = true;
                renderList();
                isAutoRefresh = wasAuto;
            }
        };
        for (int i = 0; i < animCount; i++) {
            final View card = cards.get(i);
            mainHandler.post(() -> {
                if (gen != renderGeneration || !isViewReady || card.getParent() == null) {
                    finishDissolveOne(remaining, finishAll);
                    return;
                }
                // 主线程截位图（视图只能主线程绘制），后台线程生成帧序列
                final android.graphics.Bitmap bmp = captureCardBitmap(card);
                Threads.decode().execute(() -> {
                    try {
                        final List<android.graphics.Bitmap> frames =
                                (bmp != null) ? DissolveView.buildFrames(bmp) : null;
                        if (bmp != null) bmp.recycle();
                        mainHandler.post(() -> {
                            if (gen != renderGeneration || !isViewReady || card.getParent() == null
                                    || frames == null || frames.isEmpty()) {
                                finishDissolveOne(remaining, finishAll);
                                return;
                            }
                            startDissolveAnimation(card, frames, gen, remaining, finishAll);
                        });
                    } catch (Throwable t) {
                        mainHandler.post(() -> finishDissolveOne(remaining, finishAll));
                    }
                });
            });
        }
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

    /** 计算影响网格渲染的配置指纹（竖排开关+手动列数+方向+容器宽度），切回本页时判断是否需要重建列表 */
    private String gridRenderKey() {
        try {
            Context ctx = getContext();
            if (ctx == null) return "";
            SharedPreferences prefs = ctx.getSharedPreferences("chajianzhushou_prefs", Context.MODE_PRIVATE);
            boolean manual = prefs.getBoolean(SettingsStore.KEY_GRID_MANUAL_ENABLED, false);
            int pCols = prefs.getInt(SettingsStore.KEY_GRID_MANUAL_COLUMNS_PORTRAIT, 0);
            int lCols = prefs.getInt(SettingsStore.KEY_GRID_MANUAL_COLUMNS_LANDSCAPE, 0);
            int orientation = ctx.getResources().getConfiguration().orientation;
            int width = (resultsContainer != null && resultsContainer.getWidth() > 0) ? resultsContainer.getWidth() : -1;
            return (isGridView ? "G" : "L") + "|" + (manual ? 1 : 0) + "|" + pCols + "|" + lCols
                    + "|" + orientation + "|" + width;
        } catch (Exception e) {
            return "";
        }
    }

    private int calculateGridSpanCount() {
        try {
            Context ctx = getContext();
            if (ctx == null) return 2;
            android.content.res.Resources res = ctx.getResources();
            // 手动控制每行卡片数：开启后固定 1~10，不再按屏幕自适应
            if (getGridManualColumnsEnabled()) {
                int manual = getGridManualColumnsCount();
                return Math.max(1, Math.min(10, manual));
            }
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

    /** 按当前容器宽度计算单列卡片宽度（与 calculateGridSpanCount 同一算法） */
    private int getGridColumnWidth(int spanCount) {
        try {
            Context ctx = getContext();
            if (ctx == null || spanCount <= 0) return 0;
            int gap = ctx.getResources().getDimensionPixelSize(R.dimen.grid_gap);
            int availW;
            if (resultsContainer != null && resultsContainer.getWidth() > 0) {
                availW = resultsContainer.getWidth();
            } else {
                android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
                int pagePadPx = ctx.getResources().getDimensionPixelSize(R.dimen.pad_page_h);
                availW = dm.widthPixels - pagePadPx * 2;
            }
            return Math.max(0, (availW - gap * (spanCount - 1)) / spanCount);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 生成网格卡片单元格参数：整行用均分权重；
     * 最后一行不足整列数时按正常列宽固定宽度，避免单张卡片拉伸占满整行。
     */
    private LinearLayout.LayoutParams makeGridCellParams(int c, int rowSize, int spanCount, int heightMode) {
        int perW = getGridColumnWidth(spanCount);
        LinearLayout.LayoutParams lp;
        if (rowSize >= spanCount || perW <= 0) {
            lp = new LinearLayout.LayoutParams(0, heightMode, 1.0f);
        } else {
            lp = new LinearLayout.LayoutParams(perW, heightMode);
        }
        if (c > 0) {
            int gap = getResources().getDimensionPixelSize(R.dimen.spacing_lg);
            lp.leftMargin = gap;
        }
        return lp;
    }

    /** 读取设置：是否手动固定竖向排列每行卡片数 */
    private boolean getGridManualColumnsEnabled() {
        try {
            SharedPreferences prefs = requireContext().getSharedPreferences("chajianzhushou_prefs", Context.MODE_PRIVATE);
            return prefs.getBoolean(KEY_GRID_MANUAL_ENABLED, false);
        } catch (Exception e) {
            return false;
        }
    }

    /** 读取设置：手动固定的每行卡片数（竖屏/横屏各自设置，1~10） */
    private int getGridManualColumnsCount() {
        try {
            SharedPreferences prefs = requireContext().getSharedPreferences("chajianzhushou_prefs", Context.MODE_PRIVATE);
            boolean landscape = getResources().getConfiguration().orientation
                    == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
            int v = prefs.getInt(landscape
                    ? KEY_GRID_MANUAL_COLUMNS_LANDSCAPE
                    : KEY_GRID_MANUAL_COLUMNS_PORTRAIT,
                    landscape ? 4 : 3);
            return Math.max(1, Math.min(10, v));
        } catch (Exception e) {
            return 3;
        }
    }

    private void renderList() {
        if (!isViewReady || resultsContainer == null || tvNoResults == null || tvResultCount == null) return;
        renderGeneration++;

        try {
            tvNoResults.setVisibility(View.GONE);

            int count = currentPackages.size();
            updateResultCount(currentPackages);

            if (count == 0) {
                tvNoResults.setVisibility(View.VISIBLE);
                resultsContainer.removeAllViews();
                synchronized (allImageUrls) { allImageUrls.clear(); allTrackingNos.clear(); }
                packageCardFactory.stopAllBlink();
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
                        String cached = imageUrlResolver != null ? imageUrlResolver.getCachedUrl(tno, rawPath) : null;
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
                    String iid = pkgBillCode(item);
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

                    // 懒加载差分：只处理当前已渲染的行数（首屏至少 BATCH_SIZE 条），
                    // 剩余行等滚动到底部再加载，避免每轮自动刷新全量重建全部卡片
                    int desired = Math.max(renderedCount, BATCH_SIZE);
                    int targetCount = Math.min(currentPackages.size(), desired);
                    final int targetRowCount = Math.min(newRowIds.size(), (targetCount + spanCount - 1) / spanCount);
                    // 预判断哪些旧行保持不动（内容与期望顺序完全一致 → 零闪烁）；保持不动的行里的卡片不可被挪用，否则会破坏该行
                    boolean[] keepRows = new boolean[oldRows.size()];
                    for (int ri = 0; ri < oldRows.size(); ri++) {
                        LinearLayout oldRow = oldRows.get(ri);
                        boolean same = ri < targetRowCount && oldRow.getChildCount() == newRowIds.get(ri).size();
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
                    for (int ri = 0; ri < targetRowCount; ri++) {
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
                                card = (item != null) ? packageCardFactory.createCard(item, true, spanCount) : null;
                                if (card == null) continue;
                            }
                            LinearLayout.LayoutParams cellLp = makeGridCellParams(
                                    j, rowIds.size(), spanCount, ViewGroup.LayoutParams.WRAP_CONTENT);
                            card.setLayoutParams(cellLp);
                            targetRow.addView(card);
                        }
                        if (ri >= oldRows.size()) resultsContainer.addView(targetRow);
                    }
                    // 删除多余的旧行（从末尾开始移除）
                    for (int ri = oldRows.size() - 1; ri >= targetRowCount; ri--) {
                        resultsContainer.removeView(oldRows.get(ri));
                        structuralChanged[0] = true;
                    }
                    // 懒加载：更新已渲染卡片数；还有剩余则保持滚动加载监听
                    renderedCount = Math.min(currentPackages.size(), targetRowCount * spanCount);
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
                    // 懒加载：只重建已渲染的批次（首屏至少 BATCH_SIZE 条），剩余滚动再加载
                    // 按新位置复用仍在本批次的存活卡片，避免重复渲染
                    int desired = Math.max(renderedCount, BATCH_SIZE);
                    int targetCount = Math.min(currentPackages.size(), desired);
                    java.util.Map<String, View> keptById = new java.util.HashMap<>();
                    for (View card : survivingCards) {
                        Object tag = card.getTag(R.id.btn_query);
                        if (tag != null) keptById.put(tag.toString(), card);
                    }
                    for (int idx = 0; idx < targetCount; idx++) {
                        JSONObject item = currentPackages.get(idx);
                        String itemId = pkgBillCode(item);
                        View card = keptById.remove(itemId);
                        if (card != null) {
                            resultsContainer.addView(card);
                        } else {
                            packageCardFactory.createAndAdd(resultsContainer, item, false);
                            structuralChanged[0] = true;
                        }
                    }
                    renderedCount = targetCount;
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

                // 还有未渲染的卡片：保持滚动加载监听；已全部渲染则断开
                if (renderedCount < currentPackages.size()) {
                    attachScrollLoadMoreListener();
                } else {
                    detachScrollLoadMoreListener();
                }
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
                            CardView card = packageCardFactory.createCard(item, true, spanCount);
                            LinearLayout.LayoutParams cellLp = makeGridCellParams(
                                    c, rowSize, spanCount, ViewGroup.LayoutParams.WRAP_CONTENT);
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
                        packageCardFactory.createAndAdd(resultsContainer, currentPackages.get(i), false);
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
        packageCardFactory.pruneBlink(currentPackages);
        // 记录本次渲染时的网格配置指纹，供切回本页时判断是否需要重建
        lastGridRenderKey = gridRenderKey();
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
        } catch (Throwable e) {
            try { LogRecorder.warn(requireContext(), "IMAGE", "刷新列表图片失败", e == null ? "" : String.valueOf(e.getMessage())); } catch (Exception ignore) {}
        }
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

            // URL 变化 → 重新加载（磁盘缓存按照片身份校验，照片未换则直接复用缓存图）
            ImageLoader.with(apiService.getOkHttpClient()).load(newUrl, tno, rawImgPath, iv, R.drawable.bg_image_placeholder);
            if (tno.length() > 0 && imageUrlResolver != null) imageUrlResolver.putCachedUrl(tno, rawImgPath, newUrl);

            // 若图片预览正打开且展示的正是该单号 → 同步切换大图
            try {
                ImagePreviewDialog dlg = ImagePreviewDialog.getActiveDialog();
                if (dlg != null) dlg.refreshImage(newUrl, tno);
        } catch (Throwable e) {
            try { LogRecorder.warn(requireContext(), "IMAGE", "刷新卡片图片失败", e == null ? "" : String.valueOf(e.getMessage())); } catch (Exception ignore) {}
        }
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
        if (imageUrlResolver != null) {
            return imageUrlResolver.loadCardImage(iv, trackingNumber, imageUrl, rawImgPath);
        }
        return "";
    }

    /** 图片 URL 缓存键：单号 + 原始图片路径（路径变化=换新照片，旧 URL 不可复用） */

    /**
     * 直连模式：按原始图片路径异步解析 URL 并加载。
     * 用 "raw:单号:路径" 作为 ImageView 的 URL 标记，防止卡片复用/重建后迟到的回调覆盖错误图片；
     * 解析成功的 URL 同时记入 resolvedImageUrls，供预览列表重建时补充。
     */
    private void resolveAndLoad(final ImageView iv, final String billCode, final String rawImgPath) {
        if (imageUrlResolver != null) {
            imageUrlResolver.resolveAndLoad(iv, billCode, rawImgPath, (bCode, url) -> {
                // 解析成功：补充到预览列表（供跨卡片翻页）
                synchronized (allImageUrls) {
                    if (!allImageUrls.contains(url)) {
                        allImageUrls.add(url);
                        allTrackingNos.add(billCode);
                    }
                }
            });
        }
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
                    item.optString("inSignImg", ""),
                    item.optString("imgName", ""));
            String outboundRaw = firstNonEmpty(
                    item.optString("rawImgPathOutbound", ""),
                    item.optString("fileImgPath", ""),
                    item.optString("outSignImg", ""));

            // 服务器模式：服务器已返回 imageUrl（一般为出库文件照），出库照可能由独立字段给出
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
            if (secondaryRaw.isEmpty()) {
                // 列表数据缺少另一张照片：按轨迹接口补齐（异步），保证预览可切换入库/出库图
                fetchCompareFromTrajectory(trackingNumber, item, displayRaw);
            } else {
                resolveComparePhoto(trackingNumber, secondaryRaw, secondaryName);
            }
        } catch (Throwable ignore) {}
    }

    /** 列表数据缺少对比照片时，调用轨迹接口（getStockBillLog）补齐入库/出库图 */
    private void fetchCompareFromTrajectory(final String billCode, final JSONObject item, final String displayRaw) {
        if (billCode == null || billCode.isEmpty() || directApiClient == null) return;
        try {
            final String company = resolveExpressCompanyCode(item);
            Threads.io().execute(() -> {
                try {
                    JSONObject photos = directApiClient.getStockBillLog(billCode, company);
                    if (photos == null) return;
                    final String arrival = photos.optString("arrival", "");
                    final String outbound = photos.optString("outbound", "");
                    String display = displayRaw == null ? "" : displayRaw;
                    String missing = "";
                    String missingName = "";
                    if (display.equals(arrival)) {
                        missing = outbound;
                        missingName = "出库图片";
                    } else if (display.equals(outbound)) {
                        missing = arrival;
                        missingName = "入库图片";
                    } else {
                        // 无法判断当前显示哪张：优先补入库图，其次出库图
                        missing = arrival.isEmpty() ? outbound : arrival;
                        missingName = arrival.isEmpty() ? "出库图片" : "入库图片";
                    }
                    final String m = missing;
                    final String mn = missingName;
                    if (!isViewReady) return;
                    mainHandler.post(() -> resolveComparePhoto(billCode, m, mn));
                } catch (Throwable ignore) {}
            });
        } catch (Throwable ignore) {}
    }

    /** 根据包裹字段推断快递公司编码（中通/韵达/圆通/申通/顺丰/极兔等，缺省 ZTO） */
    static String resolveExpressCompanyCode(JSONObject item) {
        if (item == null) return "ZTO";
        String code = item.optString("expressCompanyCode", "");
        if (code != null && !code.isEmpty()) return code;
        String cn = firstNonEmpty(
                item.optString("expressCompanyName", ""),
                item.optString("expressCompany", ""),
                item.optString("express", ""),
                item.optString("courier", "")).toLowerCase();
        if (cn.contains("中通") || cn.contains("zto") || cn.contains("zhongtong")) return "ZTO";
        if (cn.contains("韵达") || cn.contains("yunda") || cn.contains("yd")) return "YUNDA";
        if (cn.contains("圆通") || cn.contains("yto") || cn.contains("yuantong")) return "YTO";
        if (cn.contains("申通") || cn.contains("sto") || cn.contains("shentong")) return "STO";
        if (cn.contains("顺丰") || cn.contains("sf")) return "SF";
        if (cn.contains("极兔") || cn.contains("jt") || cn.contains("jitu")) return "JT";
        return "ZTO";
    }

    // ===== Lazy Load More =====

    private void attachScrollLoadMoreListener() {
        if (scrollView == null || !isViewReady) return;
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
    }

    private void detachScrollLoadMoreListener() {
        currentScrollListener = null;
    }

    private void loadMoreItems() {
        if (!isViewReady || resultsContainer == null || isLoadingMore) return;
        // 快速滑动连发保护：极短时间内不重复触发（120ms），既防与惯性滚动干扰，又不明显延迟加载
        if (System.currentTimeMillis() - lastUserScrollAt < 120) return;
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
                    CardView card = packageCardFactory.createCard(item, true, spanCount);
                    // 同行卡片高度统一：MATCH_PARENT 跟随行容器测量后的最高高度
                    LinearLayout.LayoutParams cellLp = makeGridCellParams(
                            c, rowSize, spanCount, ViewGroup.LayoutParams.MATCH_PARENT);
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
                packageCardFactory.createAndAdd(resultsContainer, currentPackages.get(i), false);
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
    static void applyCardPendingBorder(CardView card, boolean pending) {
        if (card == null) return;
        card.setBackgroundResource(pending
                ? R.drawable.bg_pkg_card_pending
                : R.drawable.bg_pkg_card);
    }

    /** 点击卡片图片放大预览（含跨包裹上下张翻页与出入库照片对比） */
    private void showImagePreviewForCard(ImageView iv) {
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
        } catch (Throwable ignore) {}
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
            long t = PackageCardFactory.parseTimeMillis(outTime);
            if (t <= 0) return false;
            long cutoff = System.currentTimeMillis() - getTimeoutMarkDays() * 24L * 3600 * 1000L;
            return t >= cutoff;
        } catch (Throwable ignore) {
            return false;
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
                    if ("pending".equals(p.optString("status", ""))
                            && !justOutboundBillCodes.contains(pkgBillCode(p))) pending++;
                    else if (isTimeoutPackage(p)) timeout++;
                }
            }
            int muted = getResources().getColor(R.color.muted, ctx.getTheme());
            int success = getResources().getColor(R.color.success, ctx.getTheme());

            // 前缀："xx 个包裹 · 待取 x（绿色数字） · "
            SpannableStringBuilder sb = new SpannableStringBuilder();
            sb.append(total + " 个包裹 · 待取 ");
            int n1 = sb.length();
            sb.append(String.valueOf(pending));
            sb.append(" · ");
            sb.setSpan(new ForegroundColorSpan(muted), 0, sb.length(),
                    SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new ForegroundColorSpan(success), n1, n1 + String.valueOf(pending).length(),
                    SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
            tvResultCount.setText(sb);

            // 超时出库文字（黄色）+ 跑马灯边框：有超时件时才显示"请核验"后缀并点亮跑马灯；
            // 无超时件时仅保留"超时出库 0"文字，后缀与边框整体隐藏
            if (tvResultTimeout != null) {
                tvResultTimeout.setText(timeout > 0
                        ? "超时出库 " + timeout + " (请核验是否已取走)"
                        : "超时出库 0");
            }
            if (resultCountMarquee != null) {
                resultCountMarquee.setVisibility(timeout > 0 ? View.VISIBLE : View.GONE);
            }
            if (resultCountFlow != null) {
                resultCountFlow.setFlowEnabled(timeout > 0);
            }
            if (resultCountBox != null) {
                // 与"前缀"一同隐藏/显示：未查询过时不显示整个结果数框
                resultCountBox.setVisibility(hasQueried ? View.VISIBLE : View.INVISIBLE);
            }
        } catch (Throwable ignore) {}
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
