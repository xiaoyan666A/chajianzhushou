package com.chajianzhushou.app;

import android.app.TimePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.AppCompatRadioButton;
import androidx.appcompat.widget.SwitchCompat;
import androidx.fragment.app.Fragment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Calendar;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class SettingsFragment extends Fragment {

    private static final String TAG = "SettingsFragment";
    // 图片缓存过期天数选项（天）
    private static final int[] IMAGE_CACHE_DAYS_VALUES = {7, 14, 21, 30};
    // 日志保留天数选项（天）
    private static final int[] LOG_RETAIN_DAYS_VALUES = {7, 14, 30, 90};
    // 字号重建防抖：3 秒内最多重建一次，杜绝任何意外触发的无限重建循环
    private static volatile long sLastFontRecreateAt = 0L;
    // 界面字号档位：小/中/大/特大（相对系统字号的倍率）
    private static final float[] UI_FONT_SCALE_VALUES = {0.9f, 1.0f, 1.15f, 1.3f};
    private static final String[] UI_FONT_SCALE_LABELS = {"小", "中", "大", "特大"};

    // auto_close_minutes: positions 0-8 → values in minutes
    private static final double[] AUTO_CLOSE_VALUES = {
            0.167, 0.25, 0.5, 1, 3, 5, 10, 30, 0
    };

    // auto_refresh: positions 0-10 → values in seconds (1,2,3,4,5,6,7,8,9,10,0关闭)
    private static final int[] AUTO_REFRESH_VALUES = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 0};

    // TTS voice values
    private static final String[] TTS_VOICE_VALUES = {"冰糖", "茉莉", "苏打", "白桦"};

    // TTS style values
    private static final String[] TTS_STYLE_VALUES = {"", "温柔", "活泼", "严肃", "干练", "甜美", "慵懒", "俏皮", "磁性", "清亮"};

    // State
    private boolean isViewReady = false;
    private ApiService apiService;
    private SettingsStore settingsStore;
    private Handler mainHandler;
    private boolean serverConnectEnabled = false;

    // Views - Account & System
    private TextView tvAvatar;
    private TextView tvAccountName;
    private TextView tvAccountId;
    private LinearLayout tvAccountStatus;
    private TextView tvStaffName;
    private TextView tvStaffPost;
    private TextView tvStaffAccount;
    private TextView tvStaffDepotCode;
    private TextView tvVersionFooter;
    private boolean staffInfoLoaded = false;

    // Views - Voice Recognition
    private SwitchCompat switchAsrEnabled;
    private Spinner spinnerAutoCloseMinutes;
    private Spinner spinnerAutoRefresh;
    private EditText etMimoApiKey;
    private Button btnLockMimoKey;
    private boolean mimoKeyLocked = false;

    // Views - TTS
    private SwitchCompat switchTtsEnabled;
    private Spinner spinnerTtsVoice;
    private Spinner spinnerTtsStyle;
    private EditText etTtsCustomStyle;
    private SeekBar seekTtsSpeed;
    private TextView tvTtsSpeedLabel;
    private Button btnSaveTts;
    private Button btnTestTts;

    // Views - Logs
    private SwitchCompat switchLogsEnabled;
    private Button btnViewLogs;
    private LinearLayout logModuleFilterContainer;

    // Views - Server Connection
    private EditText etServerIp;
    private Button btnSaveIp;
    private SwitchCompat switchServerConnect;
    private SwitchCompat switchSyncQuery;
    private SwitchCompat switchSyncTimeout;
    private SwitchCompat switchSyncSettings;
    private TextView tvConnectionStatus;
    private Button btnLogin;
    private Button btnLogout;

    // Views - 功能区总开关（界面显示/缓存管理/界面风格）
    private SwitchCompat switchUiDisplayEnabled;
    private SwitchCompat switchCacheMgmtEnabled;
    private SwitchCompat switchThemeEnabled;

    // Views - 各功能区子设置项容器（总开关关闭时整体隐藏）
    private View timeoutMarkContent;
    private View uiDisplayContent;
    private View cacheMgmtContent;
    private View themeContent;
    private View asrContent;
    private View ttsContent;
    private View logsContent;
    private View serverConnectContentTop;
    private View serverConnectContentBottom;

    // Views - 进阶功能（输入解锁码 admin 后显示高级配置卡片）
    private SwitchCompat switchAdvancedFeatures;
    private View cardAsr;
    private View cardTts;
    private View cardLogs;
    private View cardServerConnect;
    private View cardTimeoutMark;
    private View cardUiDisplay;
    private View cardCacheMgmt;
    private boolean advancedUnlockBusy = false;

    // Views - 界面风格（Theme）
    private View themeOptLight;
    private View themeOptDark;
    private View themeOptAuto;
    private AppCompatRadioButton rbThemeLight;
    private AppCompatRadioButton rbThemeDark;
    private AppCompatRadioButton rbThemeAuto;
    private LinearLayout autoThemePanel;
    private EditText etSunriseTime;
    private EditText etSunsetTime;
    private Button btnThemeLocate;
    private TextView tvThemeHint;
    private Runnable autoThemeTick;   // 自动模式每分钟重算一次

    // Views - 超时件标注
    private Spinner spinnerTimeoutMarkDays;
    private SwitchCompat switchTimeoutMarkEnabled;

    // Views - 界面显示（字号）
    private Spinner spinnerUiFontScale;

    // Views - 界面显示（竖向排列每行卡片数）
    private SwitchCompat switchGridManualColumns;
    private Spinner spinnerGridManualColumnsPortrait;
    private Spinner spinnerGridManualColumnsLandscape;

    // Views - 缓存管理
    private Spinner spinnerImageCacheDays;
    private Spinner spinnerLogRetainDays;
    private Button btnClearCache;

    // 定位权限申请使用 Activity Result API（替代已弃用的 requestPermissions/onRequestPermissionsResult）
    private final androidx.activity.result.ActivityResultLauncher<String[]> locationPermissionLauncher =
            registerForActivityResult(
                    new androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
                    result -> {
                        if (!isViewReady) return;
                        boolean granted = false;
                        if (result != null) {
                            Boolean fine = result.get(android.Manifest.permission.ACCESS_FINE_LOCATION);
                            Boolean coarse = result.get(android.Manifest.permission.ACCESS_COARSE_LOCATION);
                            granted = (fine != null && fine) || (coarse != null && coarse);
                        }
                        if (granted) {
                            doLocateForSunTimes();
                        } else {
                            updateThemeHint();
                            safeToast("未授权定位权限，自动模式将使用手动设置的日出日落时间");
                        }
                    });

    // Prevent spinner from firing API during initial load
    private volatile boolean isLoadingSettings = true;

    // TTS helper
    private TtsHelper ttsHelper;

    private void bindGridColumnsSpinner(Spinner spinner, final String prefKey, final String label) {
        if (spinner == null) return;
        java.util.List<String> colOptions = new java.util.ArrayList<>();
        for (int i = 1; i <= 10; i++) colOptions.add(i + " 个");
        ArrayAdapter<String> colAdapter = new ArrayAdapter<>(
                requireContext(), R.layout.spinner_item, colOptions);
        colAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        spinner.setAdapter(colAdapter);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View v, int pos, long id) {
                if (isLoadingSettings) return;
                settingsStore.set(prefKey, pos + 1);
                Log.d(TAG, label + ": " + (pos + 1));
                try { LogRecorder.info(requireContext(), "Settings", label, String.valueOf(pos + 1)); } catch (Exception ignore) {}
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void restoreGridColumnsSpinner(Spinner spinner, int cols, boolean enabled) {
        if (spinner == null) return;
        if (cols < 1) cols = 1;
        if (cols > 10) cols = 10;
        spinner.setSelection(cols - 1);
        spinner.setEnabled(enabled);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_settings, container, false);
        settingsStore = new SettingsStore(requireContext());

        // Account views
        tvAvatar = view.findViewById(R.id.tv_avatar);
        tvAccountName = view.findViewById(R.id.tv_account_name);
        tvAccountId = view.findViewById(R.id.tv_account_id);
        tvAccountStatus = view.findViewById(R.id.tv_account_status);
        tvStaffName = view.findViewById(R.id.tv_staff_name);
        tvStaffPost = view.findViewById(R.id.tv_staff_post);
        tvStaffAccount = view.findViewById(R.id.tv_staff_account);
        tvStaffDepotCode = view.findViewById(R.id.tv_staff_depot_code);
        tvVersionFooter = view.findViewById(R.id.tv_version_footer);
        // 应用版本：动态读取真实 versionName，避免与 build.gradle 中版本号不同步
        try {
            String ver = requireContext().getPackageManager()
                    .getPackageInfo(requireContext().getPackageName(), 0).versionName;
            if (tvVersionFooter != null && ver != null && ver.length() > 0) {
                tvVersionFooter.setText("版本 " + ver);
            }
        } catch (Exception ignore) {}

        // Voice recognition
        switchAsrEnabled = view.findViewById(R.id.switch_asr_enabled);
        spinnerAutoCloseMinutes = view.findViewById(R.id.spinner_auto_close_minutes);
        spinnerAutoRefresh = view.findViewById(R.id.spinner_auto_refresh);
        etMimoApiKey = view.findViewById(R.id.et_mimo_api_key);
        btnLockMimoKey = view.findViewById(R.id.btn_lock_mimo_key);

        // TTS
        switchTtsEnabled = view.findViewById(R.id.switch_tts_enabled);
        spinnerTtsVoice = view.findViewById(R.id.spinner_tts_voice);
        spinnerTtsStyle = view.findViewById(R.id.spinner_tts_style);
        etTtsCustomStyle = view.findViewById(R.id.et_tts_custom_style);
        seekTtsSpeed = view.findViewById(R.id.seek_tts_speed);
        tvTtsSpeedLabel = view.findViewById(R.id.tv_tts_speed_label);
        btnSaveTts = view.findViewById(R.id.btn_save_tts);
        btnTestTts = view.findViewById(R.id.btn_test_tts);

        // Logs
        switchLogsEnabled = view.findViewById(R.id.switch_logs_enabled);
        btnViewLogs = view.findViewById(R.id.btn_view_logs);
        logModuleFilterContainer = view.findViewById(R.id.log_module_filter_container);

        // Build log module filter chips
        buildLogModuleFilterChips();

        // Server connection
        etServerIp = view.findViewById(R.id.et_server_ip);
        btnSaveIp = view.findViewById(R.id.btn_save_ip);
        switchServerConnect = view.findViewById(R.id.switch_server_connect);
        switchSyncQuery = view.findViewById(R.id.switch_sync_query);
        switchSyncTimeout = view.findViewById(R.id.switch_sync_timeout);
        switchSyncSettings = view.findViewById(R.id.switch_sync_settings);
        tvConnectionStatus = view.findViewById(R.id.tv_connection_status);
        btnLogin = view.findViewById(R.id.btn_login);
        btnLogout = view.findViewById(R.id.btn_logout);

        // 界面风格（Theme）
        themeOptLight = view.findViewById(R.id.theme_opt_light);
        themeOptDark = view.findViewById(R.id.theme_opt_dark);
        themeOptAuto = view.findViewById(R.id.theme_opt_auto);
        rbThemeLight = view.findViewById(R.id.rb_theme_light);
        rbThemeDark = view.findViewById(R.id.rb_theme_dark);
        rbThemeAuto = view.findViewById(R.id.rb_theme_auto);
        autoThemePanel = view.findViewById(R.id.auto_theme_panel);
        etSunriseTime = view.findViewById(R.id.et_sunrise_time);
        etSunsetTime = view.findViewById(R.id.et_sunset_time);
        btnThemeLocate = view.findViewById(R.id.btn_theme_locate);
        tvThemeHint = view.findViewById(R.id.tv_theme_hint);
        bindThemeViews();

        // 显示超时件标注总开关
        switchTimeoutMarkEnabled = view.findViewById(R.id.switch_timeout_mark_enabled);
        if (switchTimeoutMarkEnabled != null) {
            switchTimeoutMarkEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isLoadingSettings) return;
                settingsStore.set(SettingsStore.KEY_TIMEOUT_MARK_ENABLED, isChecked);
                postSettings("timeoutMarkEnabled", isChecked);
                setContentVisible(timeoutMarkContent, isChecked);
                Log.d(TAG, "显示超时件标注: " + isChecked);
                try { LogRecorder.info(requireContext(), "Settings", "显示超时件标注", String.valueOf(isChecked)); } catch (Exception ignore) {}
            });
        }

        // 超时件标注：最近 N 天（1~20，默认 3）
        spinnerTimeoutMarkDays = view.findViewById(R.id.spinner_timeout_mark_days);
        if (spinnerTimeoutMarkDays != null) {
            java.util.List<String> timeoutDayOptions = new java.util.ArrayList<>();
            for (int i = 1; i <= 20; i++) timeoutDayOptions.add(i + " 天");
            ArrayAdapter<String> timeoutDayAdapter = new ArrayAdapter<>(
                    requireContext(), R.layout.spinner_item, timeoutDayOptions);
            timeoutDayAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
            spinnerTimeoutMarkDays.setAdapter(timeoutDayAdapter);
            spinnerTimeoutMarkDays.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(AdapterView<?> parent, View v, int pos, long id) {
                    if (isLoadingSettings) return;
                    int days = pos + 1;
                    settingsStore.set(SettingsStore.KEY_TIMEOUT_MARK_DAYS, days);
                    postSettings("timeoutMarkDays", days);
                    Log.d(TAG, "超时件标注天数: " + days);
                    try { LogRecorder.info(requireContext(), "Settings", "超时件标注天数", String.valueOf(days)); } catch (Exception ignore) {}
                }
                @Override public void onNothingSelected(AdapterView<?> parent) {}
            });
        }

        // 界面字号：小/中/大/特大，选择后立即重建界面生效
        spinnerUiFontScale = view.findViewById(R.id.spinner_ui_font_scale);
        if (spinnerUiFontScale != null) {
            ArrayAdapter<String> fontAdapter = new ArrayAdapter<>(
                    requireContext(), R.layout.spinner_item, UI_FONT_SCALE_LABELS);
            fontAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
            spinnerUiFontScale.setAdapter(fontAdapter);
            // 只有用户真正点击选择时才生效；初始化/回填触发的 onItemSelected 一律忽略，
            // 否则 Spinner 首次布局会自动触发一次选择 → 误执行 recreate() → 无限重建导致卡死闪退
            final boolean[] fontUserTouched = {false};
            spinnerUiFontScale.setOnTouchListener((v, event) -> {
                fontUserTouched[0] = true;
                return false; // 不消费事件，交给 Spinner 正常处理
            });
            spinnerUiFontScale.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(AdapterView<?> parent, View v, int pos, long id) {
                    float scale = UI_FONT_SCALE_VALUES[Math.min(pos, UI_FONT_SCALE_VALUES.length - 1)];
                    // 三重防线：初始化/回填期间忽略；非用户触摸忽略；
                    // 选择的值与当前实际字号相同也忽略（杜绝任何情况下触发无限重建循环）
                    if (isLoadingSettings || !fontUserTouched[0]
                            || Math.abs(scale - MainActivity.sFontScale) < 0.001f) return;
                    settingsStore.set(SettingsStore.KEY_UI_FONT_SCALE, scale);
                    MainActivity.sFontScale = scale;
                    Log.d(TAG, "界面字号: " + UI_FONT_SCALE_LABELS[pos] + " (" + scale + ")");
                    try { LogRecorder.info(requireContext(), "Settings", "界面字号", UI_FONT_SCALE_LABELS[pos]); } catch (Exception ignore) {}
                    // 延迟到选择回调之后重建 Activity，避免在 Spinner 事件派发中销毁界面
                    view.post(() -> {
                        try {
                            if (getActivity() instanceof MainActivity) {
                                long now = System.currentTimeMillis();
                                if (now - sLastFontRecreateAt < 3000) return;
                                sLastFontRecreateAt = now;
                                getActivity().recreate();
                            }
                        } catch (Throwable ignore) {}
                    });
                }
                @Override public void onNothingSelected(AdapterView<?> parent) {}
            });
        }

        // 手动控制竖向排列每行卡片数（竖屏/横屏各 1~10），关闭开关时保持自适应
        switchGridManualColumns = view.findViewById(R.id.switch_grid_manual_columns);
        spinnerGridManualColumnsPortrait = view.findViewById(R.id.spinner_grid_manual_columns_portrait);
        spinnerGridManualColumnsLandscape = view.findViewById(R.id.spinner_grid_manual_columns_landscape);
        bindGridColumnsSpinner(spinnerGridManualColumnsPortrait, SettingsStore.KEY_GRID_MANUAL_COLUMNS_PORTRAIT, "竖屏每行卡片数");
        bindGridColumnsSpinner(spinnerGridManualColumnsLandscape, SettingsStore.KEY_GRID_MANUAL_COLUMNS_LANDSCAPE, "横屏每行卡片数");
        if (switchGridManualColumns != null) {
            switchGridManualColumns.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isLoadingSettings) return;
                settingsStore.set(SettingsStore.KEY_GRID_MANUAL_ENABLED, isChecked);
                if (spinnerGridManualColumnsPortrait != null) {
                    spinnerGridManualColumnsPortrait.setEnabled(isChecked);
                }
                if (spinnerGridManualColumnsLandscape != null) {
                    spinnerGridManualColumnsLandscape.setEnabled(isChecked);
                }
                Log.d(TAG, "手动每行卡片数开关: " + isChecked);
                try { LogRecorder.info(requireContext(), "Settings", "手动每行卡片数开关", String.valueOf(isChecked)); } catch (Exception ignore) {}
            });
        }

        // 图片缓存过期天数（7/14/21/30 天）
        spinnerImageCacheDays = view.findViewById(R.id.spinner_image_cache_days);
        if (spinnerImageCacheDays != null) {
            java.util.List<String> cacheDayOptions = new java.util.ArrayList<>();
            for (int v : IMAGE_CACHE_DAYS_VALUES) cacheDayOptions.add(v + " 天");
            ArrayAdapter<String> cacheDayAdapter = new ArrayAdapter<>(
                    requireContext(), R.layout.spinner_item, cacheDayOptions);
            cacheDayAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
            spinnerImageCacheDays.setAdapter(cacheDayAdapter);
            spinnerImageCacheDays.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(AdapterView<?> parent, View v, int pos, long id) {
                    if (isLoadingSettings) return;
                    int days = IMAGE_CACHE_DAYS_VALUES[Math.min(pos, IMAGE_CACHE_DAYS_VALUES.length - 1)];
                    settingsStore.set(SettingsStore.KEY_IMAGE_CACHE_DAYS, days);
                    Log.d(TAG, "图片缓存过期天数: " + days);
                    try { LogRecorder.info(requireContext(), "Settings", "图片缓存过期天数", days + " 天"); } catch (Exception ignore) {}
                }
                @Override public void onNothingSelected(AdapterView<?> parent) {}
            });
        }

        // 日志保留天数（7/14/30/90 天）
        spinnerLogRetainDays = view.findViewById(R.id.spinner_log_retain_days);
        if (spinnerLogRetainDays != null) {
            java.util.List<String> logRetainOptions = new java.util.ArrayList<>();
            for (int v : LOG_RETAIN_DAYS_VALUES) logRetainOptions.add(v + " 天");
            ArrayAdapter<String> logRetainAdapter = new ArrayAdapter<>(
                    requireContext(), R.layout.spinner_item, logRetainOptions);
            logRetainAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
            spinnerLogRetainDays.setAdapter(logRetainAdapter);
            spinnerLogRetainDays.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(AdapterView<?> parent, View v, int pos, long id) {
                    if (isLoadingSettings) return;
                    int days = LOG_RETAIN_DAYS_VALUES[Math.min(pos, LOG_RETAIN_DAYS_VALUES.length - 1)];
                    settingsStore.set(SettingsStore.KEY_LOG_RETAIN_DAYS, days);
                    Log.d(TAG, "日志保留天数: " + days);
                    try { LogRecorder.info(requireContext(), "Settings", "日志保留天数", days + " 天"); } catch (Exception ignore) {}
                }
                @Override public void onNothingSelected(AdapterView<?> parent) {}
            });
        }

        // 一键清理缓存（图片磁盘+内存、日志）
        btnClearCache = view.findViewById(R.id.btn_clear_cache);
        if (btnClearCache != null) {
            btnClearCache.setOnClickListener(v -> {
                try {
                    new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                            .setTitle("一键清理缓存")
                            .setMessage("将清理：\n1. 包裹图片缓存（磁盘 + 内存）\n2. 运行日志\n确定清理吗？")
                            .setPositiveButton("清理", (d, w) -> {
                                try {
                                    int img = ImageCacheManager.clearAll();
                                    ImageLoader.clearCache();
                                    LogRecorder.clearAllLogs(requireContext());
                                    LogRecorder.info(requireContext(), "SETTINGS", "一键清理缓存", "图片文件=" + img);
                                    safeToast("已清理图片缓存与日志");
                                } catch (Throwable t) {
                                    safeToast("清理失败: " + t.getMessage());
                                }
                            })
                            .setNegativeButton("取消", null)
                            .show();
                } catch (Throwable ignore) {}
            });
        }

        // ===== 功能区总开关（界面显示/缓存管理/界面风格）=====
        switchUiDisplayEnabled = view.findViewById(R.id.switch_ui_display_enabled);
        switchCacheMgmtEnabled = view.findViewById(R.id.switch_cache_mgmt_enabled);
        switchThemeEnabled = view.findViewById(R.id.switch_theme_enabled);

        if (switchUiDisplayEnabled != null) {
            switchUiDisplayEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isLoadingSettings) return;
                settingsStore.set(SettingsStore.KEY_UI_DISPLAY_ENABLED, isChecked);
                applyUiDisplayEnabled(isChecked);
                Log.d(TAG, "界面显示总开关: " + isChecked);
                try { LogRecorder.info(requireContext(), "Settings", "界面显示总开关", String.valueOf(isChecked)); } catch (Exception ignore) {}
            });
        }
        if (switchCacheMgmtEnabled != null) {
            switchCacheMgmtEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isLoadingSettings) return;
                settingsStore.set(SettingsStore.KEY_CACHE_MGMT_ENABLED, isChecked);
                applyCacheMgmtEnabled(isChecked);
                Log.d(TAG, "缓存管理总开关: " + isChecked);
                try { LogRecorder.info(requireContext(), "Settings", "缓存管理总开关", String.valueOf(isChecked)); } catch (Exception ignore) {}
            });
        }
        if (switchThemeEnabled != null) {
            switchThemeEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isLoadingSettings) return;
                settingsStore.set(SettingsStore.KEY_THEME_ENABLED, isChecked);
                applyThemeEnabled(isChecked);
                Log.d(TAG, "界面风格总开关: " + isChecked);
                try { LogRecorder.info(requireContext(), "Settings", "界面风格总开关", String.valueOf(isChecked)); } catch (Exception ignore) {}
            });
        }

        // 各功能区子设置项容器
        timeoutMarkContent = view.findViewById(R.id.timeout_mark_content);
        uiDisplayContent = view.findViewById(R.id.ui_display_content);
        cacheMgmtContent = view.findViewById(R.id.cache_mgmt_content);
        themeContent = view.findViewById(R.id.theme_content);
        asrContent = view.findViewById(R.id.asr_content);
        ttsContent = view.findViewById(R.id.tts_content);
        logsContent = view.findViewById(R.id.logs_content);
        serverConnectContentTop = view.findViewById(R.id.server_connect_content_top);
        serverConnectContentBottom = view.findViewById(R.id.server_connect_content_bottom);

        // ===== 进阶功能：输入解锁码 admin 后显示高级配置卡片 =====
        switchAdvancedFeatures = view.findViewById(R.id.switch_advanced_features);
        cardAsr = view.findViewById(R.id.card_asr);
        cardTts = view.findViewById(R.id.card_tts);
        cardLogs = view.findViewById(R.id.card_logs);
        cardServerConnect = view.findViewById(R.id.card_server_connect);
        cardTimeoutMark = view.findViewById(R.id.card_timeout_mark);
        cardUiDisplay = view.findViewById(R.id.card_ui_display);
        cardCacheMgmt = view.findViewById(R.id.card_cache_mgmt);

        if (switchAdvancedFeatures != null) {
            switchAdvancedFeatures.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isLoadingSettings || advancedUnlockBusy) return;
                if (isChecked) {
                    // 先回弹，验证解锁码通过后才真正开启并显示高级配置
                    advancedUnlockBusy = true;
                    switchAdvancedFeatures.setChecked(false);
                    advancedUnlockBusy = false;
                    showAdvancedUnlockDialog();
                } else {
                    settingsStore.set(SettingsStore.KEY_ADVANCED_FEATURES_ENABLED, false);
                    applyAdvancedFeatures(false);
                    Log.d(TAG, "进阶功能: 关闭");
                    try { LogRecorder.info(requireContext(), "Settings", "进阶功能", "关闭"); } catch (Exception ignore) {}
                }
            });
        }

        apiService = new ApiService(requireContext());
        mainHandler = new Handler(Looper.getMainLooper());
        ttsHelper = TtsHelper.getInstance();
        ttsHelper.init(requireContext());
        isViewReady = true;
        isLoadingSettings = true;

        // ---- Server IP ---- //
        loadSavedIp();

        if (btnSaveIp != null) {
            btnSaveIp.setOnClickListener(v -> saveServerIp());
        }

        // ---- ASR toggle ---- //
        if (switchAsrEnabled != null) {
            switchAsrEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isLoadingSettings) return;
                settingsStore.set(SettingsStore.KEY_ASR_ENABLED, isChecked);
                postSettings("asrEnabled", isChecked);
                setContentVisible(asrContent, isChecked);
            });
        }

        // ---- TTS toggle ---- //
        if (switchTtsEnabled != null) {
            switchTtsEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isLoadingSettings) return;
                settingsStore.set(SettingsStore.KEY_TTS_ENABLED, isChecked);
                postSettings("ttsEnabled", isChecked);
                if (!isChecked && ttsHelper != null) ttsHelper.stop();
                setContentVisible(ttsContent, isChecked);
            });
        }

        // ---- Logs toggle ---- //
        if (switchLogsEnabled != null) {
            switchLogsEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isLoadingSettings) return;
                settingsStore.set(SettingsStore.KEY_LOGS_ENABLED, isChecked);
                setContentVisible(logsContent, isChecked);
            });
        }

        // ---- View Logs ---- //
        if (btnViewLogs != null) {
            btnViewLogs.setOnClickListener(v -> {
                try {
                    if (getActivity() instanceof MainActivity) {
                        ((MainActivity) getActivity()).switchPage("logs");
                    }
                } catch (Exception ignore) {}
            });
        }

        // ---- Server connect switch ---- //
        if (switchServerConnect != null) {
            switchServerConnect.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isLoadingSettings) return;
                serverConnectEnabled = isChecked;
                Log.d(TAG, "服务器连接开关: " + isChecked);
                LogRecorder.info(requireContext(), "Settings", "服务器连接开关", String.valueOf(isChecked));
                settingsStore.set(SettingsStore.KEY_SERVER_CONNECT, isChecked);
                updateSyncSubSwitches(isChecked);
                setContentVisible(serverConnectContentTop, isChecked);
                setContentVisible(serverConnectContentBottom, isChecked);
                if (isChecked) {
                    loadAccountInfo();
                }
            });
        }

        // ---- Sync sub-switches ---- //
        if (switchSyncQuery != null) {
            switchSyncQuery.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isLoadingSettings) return;
                settingsStore.set(SettingsStore.KEY_SYNC_QUERY, isChecked);
            });
        }
        if (switchSyncTimeout != null) {
            switchSyncTimeout.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isLoadingSettings) return;
                settingsStore.set(SettingsStore.KEY_SYNC_TIMEOUT, isChecked);
            });
        }
        if (switchSyncSettings != null) {
            switchSyncSettings.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isLoadingSettings) return;
                settingsStore.set(SettingsStore.KEY_SYNC_SETTINGS, isChecked);
            });
        }

        // ---- TTS Voice spinner ---- //
        if (spinnerTtsVoice != null) {
            ArrayAdapter<CharSequence> voiceAdapter = ArrayAdapter.createFromResource(
                    requireContext(), R.array.tts_voice_options, R.layout.spinner_item);
            voiceAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
            spinnerTtsVoice.setAdapter(voiceAdapter);
            spinnerTtsVoice.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(AdapterView<?> parent, View v, int pos, long id) {
                    if (isLoadingSettings) return;
                    settingsStore.set(SettingsStore.KEY_TTS_VOICE, pos);
                }
                @Override public void onNothingSelected(AdapterView<?> parent) {}
            });
        }

        // ---- TTS Style spinner ---- //
        if (spinnerTtsStyle != null) {
            ArrayAdapter<CharSequence> styleAdapter = ArrayAdapter.createFromResource(
                    requireContext(), R.array.tts_style_options, R.layout.spinner_item);
            styleAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
            spinnerTtsStyle.setAdapter(styleAdapter);
            spinnerTtsStyle.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(AdapterView<?> parent, View v, int pos, long id) {
                    if (isLoadingSettings) return;
                    settingsStore.set(SettingsStore.KEY_TTS_STYLE, pos);
                }
                @Override public void onNothingSelected(AdapterView<?> parent) {}
            });
        }

        // ---- TTS Speed seekbar ---- //
        if (seekTtsSpeed != null) {
            seekTtsSpeed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    float speed = 0.5f + progress * 0.1f;
                    if (tvTtsSpeedLabel != null) {
                        tvTtsSpeedLabel.setText(String.format("%.1f", speed));
                    }
                    if (fromUser) settingsStore.set(SettingsStore.KEY_TTS_SPEED, (int)(speed * 10));
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            });
        }

        // ---- Save TTS ---- //
        if (btnSaveTts != null) {
            btnSaveTts.setOnClickListener(v -> saveTtsSettings());
        }

        // ---- Test TTS ---- //
        if (btnTestTts != null) {
            btnTestTts.setOnClickListener(v -> testTts());
        }

        // ---- Auto-close spinner ---- //
        if (spinnerAutoCloseMinutes != null) {
            ArrayAdapter<CharSequence> closeAdapter = ArrayAdapter.createFromResource(
                    requireContext(), R.array.auto_close_minutes_options, R.layout.spinner_item);
            closeAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
            spinnerAutoCloseMinutes.setAdapter(closeAdapter);
            spinnerAutoCloseMinutes.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                    if (isLoadingSettings) return;
                    saveAutoCloseMinutes(AUTO_CLOSE_VALUES[pos]);
                    postSettings("autoCloseMinutes", AUTO_CLOSE_VALUES[pos]);
                }
                @Override public void onNothingSelected(AdapterView<?> parent) {}
            });
        }

        // ---- Auto-refresh spinner ---- //
        if (spinnerAutoRefresh != null) {
            ArrayAdapter<CharSequence> refreshAdapter = ArrayAdapter.createFromResource(
                    requireContext(), R.array.auto_refresh_options, R.layout.spinner_item);
            refreshAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
            spinnerAutoRefresh.setAdapter(refreshAdapter);
            // Restore saved position from SharedPreferences
            int savedSeconds = 0;
            try {
                SharedPreferences prefs = settingsStore.prefs();
                savedSeconds = prefs.getInt(SettingsStore.KEY_AUTO_REFRESH, 0);
            } catch (Exception ignore) {}
            int restorePos = AUTO_REFRESH_VALUES.length - 1; // default: 关闭
            for (int i = 0; i < AUTO_REFRESH_VALUES.length; i++) {
                if (AUTO_REFRESH_VALUES[i] == savedSeconds) { restorePos = i; break; }
            }
            spinnerAutoRefresh.setSelection(restorePos);

            spinnerAutoRefresh.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                    if (isLoadingSettings) return;
                    int seconds = (pos < AUTO_REFRESH_VALUES.length) ? AUTO_REFRESH_VALUES[pos] : 0;
                    settingsStore.set(SettingsStore.KEY_AUTO_REFRESH, seconds);
                    postSettings("autoRefreshInterval", seconds);
                }
                @Override public void onNothingSelected(AdapterView<?> parent) {}
            });
        }

        // ---- Mimo API Key ---- //
        String savedKey = "";
        try {
            SharedPreferences prefs = settingsStore.prefs();
            savedKey = prefs.getString("mimo_api_key", "");
            mimoKeyLocked = prefs.getBoolean("mimo_api_key_locked", false);
        } catch (Exception ignore) {}
        if (etMimoApiKey != null) {
            etMimoApiKey.setText(savedKey);
            etMimoApiKey.setEnabled(!mimoKeyLocked);
            etMimoApiKey.setFocusable(!mimoKeyLocked);
            etMimoApiKey.setFocusableInTouchMode(!mimoKeyLocked);
        }
        if (btnLockMimoKey != null) {
            btnLockMimoKey.setText(mimoKeyLocked ? "🔒" : "🔓");
            btnLockMimoKey.setOnClickListener(v -> {
                mimoKeyLocked = !mimoKeyLocked;
                if (etMimoApiKey != null) {
                    etMimoApiKey.setEnabled(!mimoKeyLocked);
                    etMimoApiKey.setFocusable(!mimoKeyLocked);
                    etMimoApiKey.setFocusableInTouchMode(!mimoKeyLocked);
                    if (mimoKeyLocked) {
                        etMimoApiKey.clearFocus();
                    }
                }
                btnLockMimoKey.setText(mimoKeyLocked ? "🔒" : "🔓");
                // Save key + locked state
                try {
                    SharedPreferences prefs = settingsStore.prefs();
                    SharedPreferences.Editor editor = prefs.edit();
                    if (etMimoApiKey != null && !mimoKeyLocked) {
                        // only update text when unlocking (allow edit), but also save when locking to ensure latest value
                    }
                    if (etMimoApiKey != null && mimoKeyLocked) {
                        editor.putString("mimo_api_key", etMimoApiKey.getText().toString().trim());
                    } else if (etMimoApiKey != null) {
                        editor.putString("mimo_api_key", etMimoApiKey.getText().toString().trim());
                    }
                    editor.putBoolean("mimo_api_key_locked", mimoKeyLocked);
                    editor.apply();
                } catch (Exception ignore) {}
                safeToast(mimoKeyLocked ? "Mimo API Key已锁定保存" : "Mimo API Key已解锁，可编辑");
            });
        }

        // ---- Login button ---- //
        if (btnLogin != null) {
            btnLogin.setOnClickListener(v -> doLogin());
        }
        // ---- 退出登录：清除本机凭据与 token，返回登录界面 ---- //
        if (btnLogout != null) {
            btnLogout.setOnClickListener(v -> logout());
        }

        // Load saved preferences
        loadLocalPrefs();

        // Load data from server only when server connection is enabled
        if (serverConnectEnabled) {
            loadAccountInfo();
            loadSettings();
        } else {
            isLoadingSettings = false;
            // 直连模式下用本机保存的兔喜账号信息填充账号卡片
            updateAccountFromLoginStore();
            loadStaffInfo();
        }

        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (isAdded() && isViewReady && serverConnectEnabled) {
            loadAccountInfo();
        } else if (isAdded() && isViewReady) {
            updateAccountFromLoginStore();
            loadStaffInfo();
        }
    }

    @Override
    public void onDestroyView() {
        stopAutoThemeTick();
        isViewReady = false;
        serverConnectEnabled = false;
        super.onDestroyView();

        tvAvatar = null;
        tvAccountName = null;
        tvAccountId = null;
        tvAccountStatus = null;
        tvStaffName = null;
        tvStaffPost = null;
        tvStaffAccount = null;
        tvStaffDepotCode = null;
        tvVersionFooter = null;
        staffInfoLoaded = false;
        switchAsrEnabled = null;
        spinnerAutoCloseMinutes = null;
        spinnerAutoRefresh = null;
        switchTtsEnabled = null;
        spinnerTtsVoice = null;
        spinnerTtsStyle = null;
        etTtsCustomStyle = null;
        seekTtsSpeed = null;
        tvTtsSpeedLabel = null;
        btnSaveTts = null;
        btnTestTts = null;
        switchLogsEnabled = null;
        btnViewLogs = null;
        logModuleFilterContainer = null;
        etServerIp = null;
        btnSaveIp = null;
        switchServerConnect = null;
        switchSyncQuery = null;
        switchSyncTimeout = null;
        switchSyncSettings = null;
        tvConnectionStatus = null;
        btnLogin = null;
        btnLogout = null;
        switchAdvancedFeatures = null;
        cardAsr = null;
        cardTts = null;
        cardLogs = null;
        cardServerConnect = null;
        cardTimeoutMark = null;
        cardUiDisplay = null;
        cardCacheMgmt = null;
        switchUiDisplayEnabled = null;
        switchCacheMgmtEnabled = null;
        switchThemeEnabled = null;
        timeoutMarkContent = null;
        uiDisplayContent = null;
        cacheMgmtContent = null;
        themeContent = null;
        asrContent = null;
        ttsContent = null;
        logsContent = null;
        serverConnectContentTop = null;
        serverConnectContentBottom = null;
        themeOptLight = null;
        themeOptDark = null;
        themeOptAuto = null;
        rbThemeLight = null;
        rbThemeDark = null;
        rbThemeAuto = null;
        autoThemePanel = null;
        etSunriseTime = null;
        etSunsetTime = null;
        btnThemeLocate = null;
        tvThemeHint = null;
        autoThemeTick = null;
        spinnerTimeoutMarkDays = null;
        switchTimeoutMarkEnabled = null;
        spinnerUiFontScale = null;
        switchGridManualColumns = null;
        spinnerGridManualColumnsPortrait = null;
        spinnerGridManualColumnsLandscape = null;
        spinnerImageCacheDays = null;
        spinnerLogRetainDays = null;
        btnClearCache = null;
        apiService = null;
        mainHandler = null;
        ttsHelper = null;
    }

    // ===== 日志模块独立开关 Chip 构建 =====

    private void buildLogModuleFilterChips() {
        if (logModuleFilterContainer == null || !isViewReady) return;
        Context ctx = getContext();
        if (ctx == null) return;
        logModuleFilterContainer.removeAllViews();

        int dp6 = (int) (6 * ctx.getResources().getDisplayMetrics().density + 0.5f);
        int dp8 = (int) (8 * ctx.getResources().getDisplayMetrics().density + 0.5f);

        for (String[] m : LogRecorder.MODULE_LIST) {
            final String moduleKey = m[0];
            final String moduleName = m[1];
            final String colorStr = m[2];
            final boolean isApp = "APP".equals(moduleKey);
            final boolean isOn = LogRecorder.isModuleEnabled(ctx, moduleKey);

            TextView chip = new TextView(ctx);
            chip.setText("● " + moduleName);
            chip.setTextSize(13);
            chip.setPadding(dp8 * 2, dp6, dp8 * 2, dp6);

            int color = android.graphics.Color.parseColor(colorStr);
            if (isOn) {
                chip.setTextColor(color);
                android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
                bg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
                bg.setCornerRadius(dp8 * 4);
                bg.setColor((color & 0x00FFFFFF) | 0x22000000);
                bg.setStroke(1, (color & 0x00FFFFFF) | 0x55000000);
                chip.setBackground(bg);
            } else {
                chip.setTextColor(ctx.getResources().getColor(R.color.muted, ctx.getTheme()));
                android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
                bg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
                bg.setCornerRadius(dp8 * 4);
                bg.setColor(0x0AFFFFFF);
                bg.setStroke(1, 0x0FFFFFFF);
                chip.setBackground(bg);
            }

            if (!isApp) {
                chip.setClickable(true);
                chip.setFocusable(true);
                chip.setOnClickListener(v -> {
                    boolean newState = !LogRecorder.isModuleEnabled(ctx, moduleKey);
                    LogRecorder.setModuleEnabled(ctx, moduleKey, newState);
                    // 重建 chips 反映新状态
                    buildLogModuleFilterChips();
                });
            } else {
                chip.setAlpha(0.7f);
            }

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp6;
            lp.bottomMargin = dp6;
            logModuleFilterContainer.addView(chip, lp);
        }
    }

    // ===== 界面风格（Theme） =====

    private void bindThemeViews() {
        if (themeOptLight != null) themeOptLight.setOnClickListener(v -> onThemeModeSelected(ThemeManager.MODE_LIGHT));
        if (themeOptDark != null) themeOptDark.setOnClickListener(v -> onThemeModeSelected(ThemeManager.MODE_DARK));
        if (themeOptAuto != null) themeOptAuto.setOnClickListener(v -> onThemeModeSelected(ThemeManager.MODE_AUTO));
        if (etSunriseTime != null) etSunriseTime.setOnClickListener(v -> showTimePicker(true));
        if (etSunsetTime != null) etSunsetTime.setOnClickListener(v -> showTimePicker(false));
        if (btnThemeLocate != null) btnThemeLocate.setOnClickListener(v -> locateForSunTimes());
    }

    private void onThemeModeSelected(String mode) {
        if (!isViewReady) return;
        // 先保存并应用（实时生效），再更新本页 UI；应用后会触发 Activity 重建，重建后由 updateThemeUI 恢复选中态
        ThemeManager.setMode(requireContext(), mode);
        if (ThemeManager.MODE_AUTO.equals(mode)) locateForSunTimes();
        postThemeSettings();
        updateThemeUI();
        startAutoThemeTick();
    }

    private void updateThemeUI() {
        if (!isViewReady) return;
        String mode = ThemeManager.getMode(requireContext());
        if (rbThemeLight != null) rbThemeLight.setChecked(ThemeManager.MODE_LIGHT.equals(mode));
        if (rbThemeDark != null) rbThemeDark.setChecked(ThemeManager.MODE_DARK.equals(mode));
        if (rbThemeAuto != null) rbThemeAuto.setChecked(ThemeManager.MODE_AUTO.equals(mode));
        setThemeOptionBg(themeOptLight, ThemeManager.MODE_LIGHT.equals(mode));
        setThemeOptionBg(themeOptDark, ThemeManager.MODE_DARK.equals(mode));
        setThemeOptionBg(themeOptAuto, ThemeManager.MODE_AUTO.equals(mode));
        if (autoThemePanel != null) {
            autoThemePanel.setVisibility(ThemeManager.MODE_AUTO.equals(mode) ? View.VISIBLE : View.GONE);
        }
        if (etSunriseTime != null) etSunriseTime.setText(ThemeManager.getSunrise(requireContext()));
        if (etSunsetTime != null) etSunsetTime.setText(ThemeManager.getSunset(requireContext()));
        updateThemeHint();
    }

    private void setThemeOptionBg(View v, boolean selected) {
        if (v == null) return;
        v.setBackgroundResource(selected ? R.drawable.bg_theme_option_selected : R.drawable.bg_theme_option);
    }

    private void showTimePicker(final boolean isSunrise) {
        if (!isViewReady || getActivity() == null) return;
        int hh = 6, mm = 0;
        String cur = isSunrise ? ThemeManager.getSunrise(requireContext()) : ThemeManager.getSunset(requireContext());
        try {
            String[] p = cur.split(":");
            hh = Integer.parseInt(p[0]);
            mm = Integer.parseInt(p[1]);
        } catch (Exception ignore) {}
        TimePickerDialog dlg = new TimePickerDialog(requireContext(), (view, h, m) -> {
            String t = String.format(java.util.Locale.US, "%02d:%02d", h, m);
            if (isSunrise) ThemeManager.setSunTimes(requireContext(), t, ThemeManager.getSunset(requireContext()));
            else ThemeManager.setSunTimes(requireContext(), ThemeManager.getSunrise(requireContext()), t);
            if (isSunrise && etSunriseTime != null) etSunriseTime.setText(t);
            if (!isSunrise && etSunsetTime != null) etSunsetTime.setText(t);
            postThemeSettings();
            updateThemeHint();
            safeToast("自动切换时间已更新：日出 " + ThemeManager.getSunrise(requireContext()) + " / 日落 " + ThemeManager.getSunset(requireContext()));
        }, hh, mm, true);
        try { dlg.show(); } catch (Exception ignore) {}
    }

    private void locateForSunTimes() {
        if (!isViewReady || getActivity() == null) return;
        // Android 6.0+ 需要运行时授权定位权限
        if (android.os.Build.VERSION.SDK_INT >= 23
                && requireContext().checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED
                && requireContext().checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            locationPermissionLauncher.launch(new String[]{
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION
            });
            return;
        }
        doLocateForSunTimes();
    }

    private void doLocateForSunTimes() {
        if (!isViewReady || getActivity() == null) return;
        if (btnThemeLocate != null) {
            btnThemeLocate.setEnabled(false);
            btnThemeLocate.setText("定位中...");
        }
        ThemeManager.requestSingleLocation(requireContext(), new ThemeManager.LocationCallback() {
            @Override public void onSuccess(final double lat, final double lng) {
                requireActivity().runOnUiThread(() -> {
                    if (!isViewReady) return;
                    if (btnThemeLocate != null) {
                        btnThemeLocate.setEnabled(true);
                        btnThemeLocate.setText("自动获取定位计算日出日落");
                    }
                    String[] t = ThemeManager.calcSunTimes(Calendar.getInstance(), lat, lng);
                    if (t != null) {
                        ThemeManager.setSunTimes(requireContext(), t[0], t[1]);
                        if (etSunriseTime != null) etSunriseTime.setText(t[0]);
                        if (etSunsetTime != null) etSunsetTime.setText(t[1]);
                        postThemeSettings();
                        updateThemeHint();
                        safeToast("已定位并计算日出 " + t[0] + " / 日落 " + t[1]);
                    } else {
                        safeToast("当前日期无法计算日出日落（极昼/极夜），请手动设置时间点");
                    }
                });
            }
            @Override public void onError(String message) {
                requireActivity().runOnUiThread(() -> {
                    if (!isViewReady) return;
                    if (btnThemeLocate != null) {
                        btnThemeLocate.setEnabled(true);
                        btnThemeLocate.setText("自动获取定位计算日出日落");
                    }
                    updateThemeHint();
                    safeToast("定位失败：" + message + "，已使用手动时间点");
                });
            }
        });
    }

    private void updateThemeHint() {
        if (tvThemeHint == null || !isViewReady) return;
        tvThemeHint.setText("自动模式将根据日出日落时间在浅色与深色之间自动切换。开启定位（需授权位置权限）后可自动计算当地日出日落时间，也可手动修改上方时间点。当前：日出 " + ThemeManager.getSunrise(requireContext()) + " / 日落 " + ThemeManager.getSunset(requireContext()));
    }

    /** 自动模式：每分钟检查一次当前是否跨越日出/日落，跨过即切换主题。 */
    private void startAutoThemeTick() {
        stopAutoThemeTick();
        if (!isViewReady || !ThemeManager.MODE_AUTO.equals(ThemeManager.getMode(requireContext()))) return;
        autoThemeTick = new Runnable() {
            @Override public void run() {
                if (!isViewReady) return;
                if (ThemeManager.MODE_AUTO.equals(ThemeManager.getMode(requireContext()))) {
                    ThemeManager.apply(requireContext());
                    mainHandler.postDelayed(this, 60000);
                }
            }
        };
        mainHandler.postDelayed(autoThemeTick, 60000);
    }

    private void stopAutoThemeTick() {
        if (autoThemeTick != null && mainHandler != null) {
            mainHandler.removeCallbacks(autoThemeTick);
        }
        autoThemeTick = null;
    }

    /** 主题设置同步到服务器（电脑端 /api/settings 会再广播回所有客户端）。 */
    private void postThemeSettings() {
        if (!isViewReady || apiService == null) return;
        try {
            JSONObject body = new JSONObject();
            body.put("themeMode", ThemeManager.getMode(requireContext()));
            body.put("sunriseTime", ThemeManager.getSunrise(requireContext()));
            body.put("sunsetTime", ThemeManager.getSunset(requireContext()));
            doPost("/api/settings", body);
        } catch (Exception ignore) {}
    }

    private void loadLocalPrefs() {
        try {
            SharedPreferences prefs = settingsStore.prefs();

            // Server connect
            serverConnectEnabled = prefs.getBoolean(SettingsStore.KEY_SERVER_CONNECT, false);
            if (switchServerConnect != null) switchServerConnect.setChecked(serverConnectEnabled);
            updateSyncSubSwitches(serverConnectEnabled);

            // Sync sub-switches
            if (switchSyncQuery != null) switchSyncQuery.setChecked(prefs.getBoolean(SettingsStore.KEY_SYNC_QUERY, true));
            if (switchSyncTimeout != null) switchSyncTimeout.setChecked(prefs.getBoolean(SettingsStore.KEY_SYNC_TIMEOUT, true));
            if (switchSyncSettings != null) switchSyncSettings.setChecked(prefs.getBoolean(SettingsStore.KEY_SYNC_SETTINGS, true));

            // ASR / TTS / Logs 开关
            if (switchAsrEnabled != null) switchAsrEnabled.setChecked(prefs.getBoolean(SettingsStore.KEY_ASR_ENABLED, false));
            if (switchTtsEnabled != null) switchTtsEnabled.setChecked(prefs.getBoolean(SettingsStore.KEY_TTS_ENABLED, false));
            if (switchLogsEnabled != null) switchLogsEnabled.setChecked(prefs.getBoolean(SettingsStore.KEY_LOGS_ENABLED, false));

            // TTS voice/spinner
            if (spinnerTtsVoice != null) spinnerTtsVoice.setSelection(prefs.getInt(SettingsStore.KEY_TTS_VOICE, 0));
            if (spinnerTtsStyle != null) spinnerTtsStyle.setSelection(prefs.getInt(SettingsStore.KEY_TTS_STYLE, 0));
            if (etTtsCustomStyle != null) etTtsCustomStyle.setText(prefs.getString(SettingsStore.KEY_TTS_CUSTOM_STYLE, ""));

            // TTS speed
            int speedVal = prefs.getInt(SettingsStore.KEY_TTS_SPEED, 10);
            if (seekTtsSpeed != null) seekTtsSpeed.setProgress(speedVal - 5);
            if (tvTtsSpeedLabel != null) tvTtsSpeedLabel.setText(String.format("%.1f", speedVal / 10.0f));

            // Auto close minutes（以字符串存储，兼容 getSavedAutoCloseMinutes）
            double closeMin = 0.5;
            try {
                String closeStr = prefs.getString(SettingsStore.KEY_AUTO_CLOSE_MINUTES, null);
                if (closeStr != null) closeMin = Double.parseDouble(closeStr);
            } catch (Exception ignore) {}
            applyAutoCloseMinutes(closeMin);

            // Auto refresh interval
            int refreshSec = prefs.getInt(SettingsStore.KEY_AUTO_REFRESH, 0);
            applyAutoRefreshInterval(refreshSec);

            // 超时件标注天数（1~20，默认3）
            if (spinnerTimeoutMarkDays != null) {
                int days = prefs.getInt(SettingsStore.KEY_TIMEOUT_MARK_DAYS, 3);
                if (days < 1) days = 1;
                if (days > 20) days = 20;
                spinnerTimeoutMarkDays.setSelection(days - 1);
            }
            // 显示超时件标注总开关（默认开启）
            if (switchTimeoutMarkEnabled != null) {
                switchTimeoutMarkEnabled.setChecked(prefs.getBoolean(SettingsStore.KEY_TIMEOUT_MARK_ENABLED, true));
            }

            // 界面字号（小/中/大/特大）
            if (spinnerUiFontScale != null) {
                float scale = prefs.getFloat(SettingsStore.KEY_UI_FONT_SCALE, 1f);
                int best = 1;
                float bestDiff = Float.MAX_VALUE;
                for (int i = 0; i < UI_FONT_SCALE_VALUES.length; i++) {
                    float diff = Math.abs(UI_FONT_SCALE_VALUES[i] - scale);
                    if (diff < bestDiff) { bestDiff = diff; best = i; }
                }
                spinnerUiFontScale.setSelection(best);
            }

            // 手动控制竖向排列每行卡片数（竖屏/横屏各 1~10）
            if (switchGridManualColumns != null) {
                boolean manual = prefs.getBoolean(SettingsStore.KEY_GRID_MANUAL_ENABLED, false);
                switchGridManualColumns.setChecked(manual);
                restoreGridColumnsSpinner(spinnerGridManualColumnsPortrait,
                        prefs.getInt(SettingsStore.KEY_GRID_MANUAL_COLUMNS_PORTRAIT, 3), manual);
                restoreGridColumnsSpinner(spinnerGridManualColumnsLandscape,
                        prefs.getInt(SettingsStore.KEY_GRID_MANUAL_COLUMNS_LANDSCAPE, 4), manual);
            }

            // 图片缓存过期天数（7/14/21/30）
            if (spinnerImageCacheDays != null) {
                int days = prefs.getInt(SettingsStore.KEY_IMAGE_CACHE_DAYS, 7);
                int best = 0;
                int bestDiff = Integer.MAX_VALUE;
                for (int i = 0; i < IMAGE_CACHE_DAYS_VALUES.length; i++) {
                    int diff = Math.abs(IMAGE_CACHE_DAYS_VALUES[i] - days);
                    if (diff < bestDiff) { bestDiff = diff; best = i; }
                }
                spinnerImageCacheDays.setSelection(best);
            }

            // 日志保留天数（7/14/30/90）
            if (spinnerLogRetainDays != null) {
                int days = prefs.getInt(SettingsStore.KEY_LOG_RETAIN_DAYS, 30);
                int best = 0;
                int bestDiff = Integer.MAX_VALUE;
                for (int i = 0; i < LOG_RETAIN_DAYS_VALUES.length; i++) {
                    int diff = Math.abs(LOG_RETAIN_DAYS_VALUES[i] - days);
                    if (diff < bestDiff) { bestDiff = diff; best = i; }
                }
                spinnerLogRetainDays.setSelection(best);
            }

            // Mimo API Key
            if (etMimoApiKey != null) etMimoApiKey.setText(prefs.getString("mimo_api_key", ""));
            mimoKeyLocked = prefs.getBoolean("mimo_api_key_locked", false);
            if (etMimoApiKey != null) {
                etMimoApiKey.setEnabled(!mimoKeyLocked);
                etMimoApiKey.setFocusable(!mimoKeyLocked);
                etMimoApiKey.setFocusableInTouchMode(!mimoKeyLocked);
            }
            if (btnLockMimoKey != null) {
                btnLockMimoKey.setText(mimoKeyLocked ? "🔒" : "🔓");
            }

            // 界面风格：恢复选中态 + 自动模式启动每分钟检查
            updateThemeUI();
            startAutoThemeTick();

            // 功能区总开关（默认开启），并按状态禁用对应区域配置
            if (switchUiDisplayEnabled != null) {
                switchUiDisplayEnabled.setChecked(prefs.getBoolean(SettingsStore.KEY_UI_DISPLAY_ENABLED, true));
            }
            if (switchCacheMgmtEnabled != null) {
                switchCacheMgmtEnabled.setChecked(prefs.getBoolean(SettingsStore.KEY_CACHE_MGMT_ENABLED, true));
            }
            if (switchThemeEnabled != null) {
                switchThemeEnabled.setChecked(prefs.getBoolean(SettingsStore.KEY_THEME_ENABLED, true));
            }
            applyUiDisplayEnabled(switchUiDisplayEnabled != null && switchUiDisplayEnabled.isChecked());
            applyCacheMgmtEnabled(switchCacheMgmtEnabled != null && switchCacheMgmtEnabled.isChecked());
            applyThemeEnabled(switchThemeEnabled != null && switchThemeEnabled.isChecked());
            // 同步恢复各功能区子设置项容器的可见性（含已有总开关的卡片）
            applyAllSectionVisibility();
            // 进阶功能：恢复开关状态并应用高级配置卡片显隐
            if (switchAdvancedFeatures != null) {
                switchAdvancedFeatures.setChecked(prefs.getBoolean(SettingsStore.KEY_ADVANCED_FEATURES_ENABLED, false));
            }
            applyAdvancedFeatures(switchAdvancedFeatures != null && switchAdvancedFeatures.isChecked());
        } catch (Exception ignore) {}
    }

    private void updateSyncSubSwitches(boolean enabled) {
        if (switchSyncQuery != null) switchSyncQuery.setEnabled(enabled);
        if (switchSyncTimeout != null) switchSyncTimeout.setEnabled(enabled);
        if (switchSyncSettings != null) switchSyncSettings.setEnabled(enabled);
        if (tvConnectionStatus != null) {
            tvConnectionStatus.setText(enabled ? "正在连接..." : "未连接");
            try {
                tvConnectionStatus.setTextColor(enabled
                        ? getResources().getColor(R.color.accent, requireContext().getTheme())
                        : getResources().getColor(R.color.muted, requireContext().getTheme()));
            } catch (Exception ignore) {}
        }
    }

    // ===== 功能区总开关禁用逻辑 =====

    private void applyUiDisplayEnabled(boolean on) {
        setContentVisible(uiDisplayContent, on);
        setGroupEnabled(spinnerUiFontScale, on);
        setGroupEnabled(switchGridManualColumns, on);
        boolean manual = switchGridManualColumns != null && switchGridManualColumns.isChecked();
        setGroupEnabled(spinnerGridManualColumnsPortrait, on && manual);
        setGroupEnabled(spinnerGridManualColumnsLandscape, on && manual);
    }

    private void applyCacheMgmtEnabled(boolean on) {
        setContentVisible(cacheMgmtContent, on);
        setGroupEnabled(spinnerImageCacheDays, on);
        setGroupEnabled(spinnerLogRetainDays, on);
        setGroupEnabled(btnClearCache, on);
    }

    private void applyThemeEnabled(boolean on) {
        setContentVisible(themeContent, on);
        if (themeOptLight != null) { themeOptLight.setEnabled(on); themeOptLight.setClickable(on); }
        if (themeOptDark != null) { themeOptDark.setEnabled(on); themeOptDark.setClickable(on); }
        if (themeOptAuto != null) { themeOptAuto.setEnabled(on); themeOptAuto.setClickable(on); }
        setGroupEnabled(rbThemeLight, on);
        setGroupEnabled(rbThemeDark, on);
        setGroupEnabled(rbThemeAuto, on);
        if (etSunriseTime != null) { etSunriseTime.setEnabled(on); etSunriseTime.setClickable(on); }
        if (etSunsetTime != null) { etSunsetTime.setEnabled(on); etSunsetTime.setClickable(on); }
        setGroupEnabled(btnThemeLocate, on);
    }

    /** 根据各功能区总开关状态统一恢复子设置项容器的可见性（含已有总开关的卡片） */
    private void applyAllSectionVisibility() {
        setContentVisible(timeoutMarkContent, switchTimeoutMarkEnabled != null && switchTimeoutMarkEnabled.isChecked());
        setContentVisible(uiDisplayContent, switchUiDisplayEnabled != null && switchUiDisplayEnabled.isChecked());
        setContentVisible(cacheMgmtContent, switchCacheMgmtEnabled != null && switchCacheMgmtEnabled.isChecked());
        setContentVisible(themeContent, switchThemeEnabled != null && switchThemeEnabled.isChecked());
        setContentVisible(asrContent, switchAsrEnabled != null && switchAsrEnabled.isChecked());
        setContentVisible(ttsContent, switchTtsEnabled != null && switchTtsEnabled.isChecked());
        setContentVisible(logsContent, switchLogsEnabled != null && switchLogsEnabled.isChecked());
        boolean serverOn = switchServerConnect != null && switchServerConnect.isChecked();
        setContentVisible(serverConnectContentTop, serverOn);
        setContentVisible(serverConnectContentBottom, serverOn);
    }

    private void setContentVisible(View v, boolean visible) {
        if (v != null) {
            try { v.setVisibility(visible ? View.VISIBLE : View.GONE); } catch (Exception ignore) {}
        }
    }

    private void setGroupEnabled(View v, boolean enabled) {
        if (v != null) {
            try { v.setEnabled(enabled); } catch (Exception ignore) {}
        }
    }

    // ===== 进阶功能（解锁码 admin）=====

    /** 根据进阶功能开关状态显示/隐藏高级配置卡片 */
    private void applyAdvancedFeatures(boolean on) {
        setContentVisible(cardAsr, on);
        setContentVisible(cardTts, on);
        setContentVisible(cardLogs, on);
        setContentVisible(cardServerConnect, on);
        setContentVisible(cardTimeoutMark, on);
        setContentVisible(cardUiDisplay, on);
        setContentVisible(cardCacheMgmt, on);
    }

    /** 打开进阶功能时弹出解锁码输入框，输入 admin 通过后才真正开启 */
    private void showAdvancedUnlockDialog() {
        if (!isViewReady) return;
        final EditText et = new EditText(requireContext());
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        et.setHint("请输入解锁码");
        et.setTextSize(17f);
        et.setBackgroundResource(R.drawable.bg_input);
        try {
            int pad = getResources().getDimensionPixelSize(R.dimen.spacing_lg);
            et.setPadding(pad, pad, pad, pad);
        } catch (Exception ignore) {}
        try {
            et.setTextColor(getResources().getColor(R.color.ink, requireContext().getTheme()));
            et.setHintTextColor(getResources().getColor(R.color.muted, requireContext().getTheme()));
        } catch (Exception ignore) {}

        new AlertDialog.Builder(requireContext())
                .setTitle("进阶功能")
                .setMessage("请输入解锁码以显示超时件标注、界面显示、缓存管理、语音识别、TTS、日志输出、服务器连接配置")
                .setView(et)
                .setPositiveButton("确认", (d, w) -> {
                    String code = et.getText().toString().trim();
                    if ("admin".equals(code)) {
                        settingsStore.set(SettingsStore.KEY_ADVANCED_FEATURES_ENABLED, true);
                        // 置位开关（busy 标志防止监听器再次弹出解锁框）
                        advancedUnlockBusy = true;
                        if (switchAdvancedFeatures != null) switchAdvancedFeatures.setChecked(true);
                        advancedUnlockBusy = false;
                        applyAdvancedFeatures(true);
                        Log.d(TAG, "进阶功能: 解锁成功");
                        try { LogRecorder.info(requireContext(), "Settings", "进阶功能", "解锁成功"); } catch (Exception ignore) {}
                        safeToast("进阶功能已开启");
                    } else {
                        safeToast("解锁码错误");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ===== Helpers =====

    private void safeToast(String msg) {
        if (!isAdded() || !isViewReady) return;
        Context ctx = getContext();
        if (ctx == null) return;
        try { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show(); } catch (Exception ignore) {}
    }

    private void saveAutoCloseMinutes(double value) {
        settingsStore.set(SettingsStore.KEY_AUTO_CLOSE_MINUTES, String.valueOf(value));
    }

    private double getSavedAutoCloseMinutes() {
        return settingsStore.getDoubleMinutes(SettingsStore.KEY_AUTO_CLOSE_MINUTES, 1);
    }

    private void postSettings(String key, boolean value) {
        postSettingsObj(key, value);
    }

    private void postSettings(String key, double value) {
        postSettingsObj(key, value);
    }

    private void postSettings(String key, int value) {
        postSettingsObj(key, value);
    }

    private void postSettingsObj(String key, Object value) {
        if (!isViewReady || apiService == null) return;
        try {
            JSONObject body = new JSONObject();
            body.put(key, value);
            doPost("/api/settings", body);
        } catch (Exception e) {
            // ignore silently
        }
    }

    private void doPost(String path, JSONObject body) {
        if (!isViewReady || apiService == null) return;
        try {
            String url = apiService.getBaseUrl() + path;
            Request request = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create(body.toString(), MediaType.parse("application/json")))
                    .build();
            apiService.getOkHttpClient().newCall(request).enqueue(new Callback() {
                @Override public void onFailure(Call call, IOException e) {}
                @Override public void onResponse(Call call, Response response) throws IOException {
                    if (response.body() != null) response.body().close();
                }
            });
        } catch (Exception ignore) {}
    }

    // ===== TTS Save & Test =====

    private void saveTtsSettings() {
        if (!isViewReady) return;

        try {
            int voicePos = spinnerTtsVoice != null ? spinnerTtsVoice.getSelectedItemPosition() : 0;
            int stylePos = spinnerTtsStyle != null ? spinnerTtsStyle.getSelectedItemPosition() : 0;
            String customStyle = etTtsCustomStyle != null ? etTtsCustomStyle.getText().toString().trim() : "";
            float speed = 1.0f;
            if (seekTtsSpeed != null) speed = 0.5f + seekTtsSpeed.getProgress() * 0.1f;

            settingsStore.set(SettingsStore.KEY_TTS_VOICE, voicePos);
            settingsStore.set(SettingsStore.KEY_TTS_STYLE, stylePos);
            settingsStore.set(SettingsStore.KEY_TTS_CUSTOM_STYLE, customStyle);
            settingsStore.set(SettingsStore.KEY_TTS_SPEED, (int)(speed * 10));

            // Sync to server
            JSONObject body = new JSONObject();
            String voice = voicePos < TTS_VOICE_VALUES.length ? TTS_VOICE_VALUES[voicePos] : "冰糖";
            String style = stylePos < TTS_STYLE_VALUES.length ? TTS_STYLE_VALUES[stylePos] : "";
            body.put("ttsVoice", voice);
            body.put("ttsStyle", style);
            body.put("ttsCustomStyle", customStyle);
            body.put("ttsSpeed", String.valueOf(speed));
            doPost("/api/settings", body);

            Log.d(TAG, "TTS设置已保存: voice=" + voice + " style=" + style + " speed=" + speed);
            try { LogRecorder.info(requireContext(), "Settings", "TTS设置已保存", "voice=" + voice + " style=" + style + " speed=" + speed); } catch (Exception ignore) {}
            safeToast("TTS设置已保存");
        } catch (Exception e) {
            safeToast("保存失败: " + e.getMessage());
        }
    }

    private void testTts() {
        if (!isViewReady || ttsHelper == null) return;

        int voicePos = spinnerTtsVoice != null ? spinnerTtsVoice.getSelectedItemPosition() : 0;
        String voice = voicePos < TTS_VOICE_VALUES.length ? TTS_VOICE_VALUES[voicePos] : "冰糖";
        int stylePos = spinnerTtsStyle != null ? spinnerTtsStyle.getSelectedItemPosition() : 0;
        String style = stylePos < TTS_STYLE_VALUES.length ? TTS_STYLE_VALUES[stylePos] : "";
        String customStyle = etTtsCustomStyle != null ? etTtsCustomStyle.getText().toString().trim() : "";
        float speed = 1.0f;
        if (seekTtsSpeed != null) speed = 0.5f + seekTtsSpeed.getProgress() * 0.1f;

        String testText = "这是查件助手语音播报测试，当前音色为" + voice;
        if (customStyle.length() > 0) testText += "，风格为" + customStyle;
        else if (style.length() > 0) testText += "，风格为" + style;

        ttsHelper.speak(requireContext(), testText, new TtsHelper.TtsCallback() {
            @Override public void onDone() {}
            @Override public void onError(String error) {
                safeToast("试听失败: " + error);
            }
        });
    }

    // ===== Account Info =====

    private void loadAccountInfo() {
        if (!isViewReady || apiService == null) return;

        try {
            Request request = new Request.Builder()
                    .url(apiService.getBaseUrl() + "/api/auth/status")
                    .get()
                    .build();

            apiService.getOkHttpClient().newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    if (!isViewReady) return;
                    mainHandler.post(() -> {
                        updateAccountStatus(false);
                        if (tvAccountName != null) tvAccountName.setText("查件助手");
                        if (tvAccountId != null) tvAccountId.setText("ID: --");
                        updateConnectionStatus(false);
                    });
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    if (!isViewReady) return;
                    String body = response.body() != null ? response.body().string() : "{}";
                    try {
                        JSONObject json = new JSONObject(body);
                        mainHandler.post(() -> {
                            updateAccountFromResponse(json);
                            updateConnectionStatus(true);
                        });
                    } catch (Exception e) {
                        mainHandler.post(() -> {
                            updateAccountStatus(false);
                            if (tvAccountName != null) tvAccountName.setText("查件助手");
                            if (tvAccountId != null) tvAccountId.setText("ID: --");
                            updateConnectionStatus(false);
                        });
                    }
                }
            });
        } catch (Exception e) {
            safeToast("获取账号信息失败: " + e.getMessage());
        }
    }

    private void updateConnectionStatus(boolean connected) {
        if (!isViewReady || tvConnectionStatus == null) return;
        Log.d(TAG, "连接状态更新: " + (connected ? "已连接" : "无法连接"));
        try { LogRecorder.info(requireContext(), "Settings", "连接状态更新", (connected ? "已连接" : "无法连接")); } catch (Exception ignore) {}
        if (connected) {
            tvConnectionStatus.setText("已连接");
            try {
                tvConnectionStatus.setTextColor(getResources().getColor(R.color.success, requireContext().getTheme()));
            } catch (Exception ignore) {}
        } else {
            tvConnectionStatus.setText("无法连接");
            try {
                tvConnectionStatus.setTextColor(getResources().getColor(R.color.danger, requireContext().getTheme()));
            } catch (Exception ignore) {}
        }
    }

    private void updateAccountFromResponse(JSONObject json) {
        if (!isViewReady) return;

        try {
            JSONObject data = json;
            if (json.has("data") && !json.isNull("data")) {
                Object d = json.get("data");
                if (d instanceof JSONObject) data = (JSONObject) d;
            }

            boolean connected = data.optBoolean("connected", false)
                    || data.optBoolean("ok", false)
                    || data.optBoolean("authenticated", false);

            String name = data.optString("name", "");
            if (name.isEmpty()) name = data.optString("accountName", "");
            if (name.isEmpty()) name = data.optString("username", "");
            if (name.isEmpty()) name = data.optString("siteName", "查件助手");
            if (name.isEmpty() || name.equals("null")) name = "查件助手";

            String accountId = data.optString("accountId", "");
            if (accountId.isEmpty()) accountId = data.optString("siteName", "");
            if (accountId.isEmpty()) accountId = data.optString("id", "");
            if (accountId.isEmpty()) accountId = data.optString("uid", "");
            if (accountId.isEmpty() || accountId.equals("null")) accountId = "--";

            if (tvAccountName != null) tvAccountName.setText(name);
            if (tvAccountId != null) tvAccountId.setText("ID: " + accountId);

            updateAccountStatus(connected);
        } catch (Exception e) {
            updateAccountStatus(false);
        }
    }

    private void updateAccountStatus(boolean connected) {
        if (!isViewReady || tvAccountStatus == null) return;

        try {
            View dot = tvAccountStatus.getChildAt(0);
            TextView statusText = null;
            if (tvAccountStatus.getChildCount() > 1 && tvAccountStatus.getChildAt(1) instanceof TextView) {
                statusText = (TextView) tvAccountStatus.getChildAt(1);
            }

            if (connected) {
                if (dot != null) dot.setBackgroundResource(R.drawable.bg_status_pending);
                if (statusText != null) {
                    statusText.setText("已连接");
                    statusText.setTextColor(tvAccountStatus.getResources().getColor(R.color.accent, requireContext().getTheme()));
                }
                tvAccountStatus.setBackgroundResource(R.drawable.bg_status_pending);
            } else {
                if (dot != null) dot.setBackgroundResource(R.drawable.bg_status_delivered);
                if (statusText != null) {
                    statusText.setText("未连接");
                    statusText.setTextColor(tvAccountStatus.getResources().getColor(R.color.danger, requireContext().getTheme()));
                }
                tvAccountStatus.setBackgroundResource(R.drawable.bg_status_delivered);
            }
        } catch (Exception ignore) {}
    }

    // ===== Settings Loading =====

    private void loadSettings() {
        if (!isViewReady || apiService == null) return;

        apiService.getSettings(new ApiService.ApiCallback() {
            @Override
            public void onSuccess(JSONObject response) {
                if (!isViewReady) return;
                mainHandler.post(() -> applySettings(response));
            }

            @Override
            public void onError(String error) {
                if (!isViewReady) return;
                mainHandler.post(() -> {
                    isLoadingSettings = false;
                    applyLocalDefaults();
                });
            }
        });
    }

    private void applySettings(JSONObject json) {
        if (!isViewReady) return;

        try {
            JSONObject data = json;
            if (json.has("data") && !json.isNull("data")) {
                Object d = json.get("data");
                if (d instanceof JSONObject) data = (JSONObject) d;
            }

            if (data.has("asrEnabled") && switchAsrEnabled != null) {
                switchAsrEnabled.setChecked(data.optBoolean("asrEnabled", true));
            }
            if (data.has("ttsEnabled") && switchTtsEnabled != null) {
                switchTtsEnabled.setChecked(data.optBoolean("ttsEnabled", true));
            }
            if (data.has("autoCloseMinutes")) {
                applyAutoCloseMinutes(data.optDouble("autoCloseMinutes", 1));
            } else {
                applyAutoCloseMinutes(getSavedAutoCloseMinutes());
            }
            if (data.has("autoRefreshInterval")) {
                applyAutoRefreshInterval(data.optInt("autoRefreshInterval", 5));
            }
            if (data.has("timeoutMarkDays")) {
                applyTimeoutMarkDays(data.optInt("timeoutMarkDays", 3));
            }
            if (data.has("timeoutMarkEnabled") && switchTimeoutMarkEnabled != null) {
                switchTimeoutMarkEnabled.setChecked(data.optBoolean("timeoutMarkEnabled", true));
            }
            // 界面风格（服务器 → 本地，电脑端/其他手机端改过后同步生效）
            if (data.has("themeMode")) {
                String m = data.optString("themeMode", "");
                if (ThemeManager.MODE_LIGHT.equals(m) || ThemeManager.MODE_DARK.equals(m) || ThemeManager.MODE_AUTO.equals(m)) {
                    ThemeManager.setMode(requireContext(), m);
                }
            }
            if (data.has("sunriseTime") || data.has("sunsetTime")) {
                ThemeManager.setSunTimes(requireContext(),
                        data.optString("sunriseTime", ThemeManager.getSunrise(requireContext())),
                        data.optString("sunsetTime", ThemeManager.getSunset(requireContext())));
            }
            updateThemeUI();
            startAutoThemeTick();
        } catch (Exception e) {
            applyLocalDefaults();
        }

        isLoadingSettings = false;
    }

    private void applyLocalDefaults() {
        if (!isViewReady) return;
        applyAutoCloseMinutes(getSavedAutoCloseMinutes());
    }

    private void applyAutoCloseMinutes(double value) {
        if (spinnerAutoCloseMinutes == null) return;
        int bestPos = 3;
        double bestDiff = Double.MAX_VALUE;
        for (int i = 0; i < AUTO_CLOSE_VALUES.length; i++) {
            double diff = Math.abs(AUTO_CLOSE_VALUES[i] - value);
            if (diff < bestDiff) { bestDiff = diff; bestPos = i; }
        }
        spinnerAutoCloseMinutes.setSelection(bestPos);
    }

    private void applyAutoRefreshInterval(int value) {
        if (spinnerAutoRefresh == null) return;
        int bestPos = 3;
        int bestDiff = Integer.MAX_VALUE;
        for (int i = 0; i < AUTO_REFRESH_VALUES.length; i++) {
            int diff = Math.abs(AUTO_REFRESH_VALUES[i] - value);
            if (diff < bestDiff) { bestDiff = diff; bestPos = i; }
        }
        spinnerAutoRefresh.setSelection(bestPos);
    }

    private void applyTimeoutMarkDays(int days) {
        if (spinnerTimeoutMarkDays == null) return;
        if (days < 1) days = 1;
        if (days > 20) days = 20;
        spinnerTimeoutMarkDays.setSelection(days - 1);
    }

    // ===== Server IP =====

    private void loadSavedIp() {
        if (!isViewReady || apiService == null || etServerIp == null) return;
        try {
            String ip = apiService.getSavedServerIp();
            int port = 3000;
            etServerIp.setText(ip + ":" + port);
        } catch (Exception ignore) {}
    }

    private void saveServerIp() {
        if (!isViewReady || etServerIp == null || apiService == null) return;
        String raw = etServerIp.getText().toString().trim();
        if (raw.isEmpty()) { safeToast("请输入服务器IP地址"); return; }
        String ip = raw.contains(":") ? raw.substring(0, raw.indexOf(":")).trim() : raw;
        Log.d(TAG, "保存服务器IP: " + ip);
        try { LogRecorder.info(requireContext(), "Settings", "保存服务器IP", ip); } catch (Exception ignore) {}
        try {
            apiService.saveServerIp(ip);
            safeToast("已保存，请重启应用");
        } catch (Exception e) {
            safeToast("保存IP失败: " + e.getMessage());
        }
    }

    private void doLogin() {
        if (!isViewReady || btnLogin == null) return;
        final LoginStore store = new LoginStore(requireContext());
        if (!store.hasCredentials()) {
            safeToast("未保存兔喜账号，请先退出登录并在登录界面登录");
            return;
        }
        Log.d(TAG, "使用保存的凭据重新登录（不依赖电脑端）");
        try { LogRecorder.info(requireContext(), "Settings", "重新登录", "使用保存的凭据"); } catch (Exception ignore) {}
        try { btnLogin.setEnabled(false); btnLogin.setText("登录中..."); } catch (Exception ignore) {}

        Threads.io().execute(() -> {
            boolean success = false;
            String errMsg = "未知错误";
            String userId = "";
            try {
                JSONObject authResult = DirectApiClient.login(requireContext(), store.getUsername(), store.getPassword());
                userId = authResult == null ? "" : authResult.optString("userId", "");
                success = authResult != null && userId.length() > 0;
                if (!success) errMsg = "登录失败，userId为空";
            } catch (Exception e) {
                errMsg = e.getMessage() == null ? "登录异常" : e.getMessage();
                Log.w(TAG, "重新登录出错: " + errMsg);
                try { LogRecorder.error(requireContext(), "Settings", "重新登录出错", errMsg); } catch (Exception ignore) {}
            }

            final boolean finalOk = success;
            final String finalErr = errMsg;
            mainHandler.post(() -> {
                if (!isViewReady) return;
                if (btnLogin != null) {
                    try { btnLogin.setEnabled(true); btnLogin.setText("重新登录"); } catch (Exception ignore) {}
                }
                if (finalOk) {
                    updateAccountFromLoginStore();
                    updateConnectionStatus(true);
                    safeToast("登录成功");
                } else {
                    updateConnectionStatus(false);
                    safeToast("登录失败: " + finalErr);
                }
            });
        });
    }

    /** 直连模式下用本机保存的兔喜账号信息填充账号卡片 */
    private void updateAccountFromLoginStore() {
        if (!isViewReady) return;
        try {
            LoginStore store = new LoginStore(requireContext());
            if (!store.hasCredentials()) return;
            if (tvAccountName != null) tvAccountName.setText(store.getMaskedUsername());
            String uid = store.getUserId();
            if (tvAccountId != null) {
                tvAccountId.setText(uid.length() > 0 ? "ID: " + uid : "ID: --");
            }
            if (tvAccountStatus != null) {
                tvAccountStatus.setBackgroundResource(R.drawable.bg_status_pending);
                int childCount = tvAccountStatus.getChildCount();
                for (int i = 0; i < childCount; i++) {
                    View child = tvAccountStatus.getChildAt(i);
                    if (child instanceof TextView) {
                        ((TextView) child).setText("已登录");
                        ((TextView) child).setTextColor(
                                requireContext().getResources().getColor(R.color.accent, requireContext().getTheme()));
                    }
                }
            }
        } catch (Exception ignore) {}
    }

    /** 直连模式下异步加载门店信息与账号信息（getStaffByStaffCodeWithLoginCheck），成功后覆盖填充 */
    private void loadStaffInfo() {
        if (!isViewReady || staffInfoLoaded) return;
        staffInfoLoaded = true;
        try {
            final DirectApiClient client = new DirectApiClient(requireContext());
            Threads.io().execute(() -> {
                try {
                    final JSONObject info = client.getStaffInfo();
                    if (info == null) return;
                    mainHandler.post(() -> applyStaffInfo(info));
                } catch (Exception e) {
                    Log.w(TAG, "获取门店/账号信息失败: " + e.getMessage());
                }
            });
        } catch (Exception e) {
            Log.w(TAG, "获取门店/账号信息异常: " + e.getMessage());
        }
    }

    /** 将门店/账号信息填充到账号信息卡片 */
    private void applyStaffInfo(JSONObject info) {
        if (!isViewReady) return;
        try {
            String depotShortName = info.optString("depotShortName", "");
            String name = info.optString("name", "");
            String account = info.optString("account", "");
            String depotCode = info.optString("depotCode", "");
            String id = info.optString("id", "");
            String postName = "";
            if (info.has("posts") && !info.isNull("posts")) {
                JSONArray posts = info.getJSONArray("posts");
                if (posts.length() > 0) {
                    postName = posts.getJSONObject(0).optString("postName", "");
                }
            }
            if (tvAccountName != null && depotShortName.length() > 0) {
                tvAccountName.setText(depotShortName);
            }
            if (tvAccountId != null) {
                tvAccountId.setText(id.length() > 0 ? "ID: " + id : "ID: --");
            }
            if (tvStaffName != null && name.length() > 0) tvStaffName.setText(name);
            if (tvStaffPost != null && postName.length() > 0) tvStaffPost.setText(postName);
            if (tvStaffAccount != null && account.length() > 0) tvStaffAccount.setText(account);
            if (tvStaffDepotCode != null && depotCode.length() > 0) tvStaffDepotCode.setText(depotCode);
        } catch (Exception ignore) {}
    }

    /** 退出登录：确认后清除本机凭据与 token，返回登录界面 */
    private void logout() {
        if (!isViewReady) return;
        new AlertDialog.Builder(requireContext())
                .setTitle("退出登录")
                .setMessage("将清除本机保存的兔喜账号与登录令牌，并返回登录界面。确定退出吗？")
                .setPositiveButton("退出", (d, w) -> {
                    try {
                        new LoginStore(requireContext()).clearAll();
                        LogRecorder.info(requireContext(), "Settings", "退出登录", "已清除本机凭据与token");
                    } catch (Exception ignore) {}
                    try {
                        Intent i = new Intent(requireContext(), LoginActivity.class);
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        startActivity(i);
                    } catch (Exception ignore) {}
                    if (getActivity() != null) getActivity().finish();
                })
                .setNegativeButton("取消", null)
                .show();
    }
}
