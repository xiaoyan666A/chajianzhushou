package com.chajianzhushou.app;

import android.app.TimePickerDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import androidx.appcompat.widget.AppCompatRadioButton;
import androidx.appcompat.widget.SwitchCompat;
import androidx.fragment.app.Fragment;

import org.json.JSONObject;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Calendar;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class SettingsFragment extends Fragment {

    private static final String TAG = "SettingsFragment";
    private static final String PREFS_NAME = "chajianzhushou_prefs";
    private static final String KEY_AUTO_CLOSE_MINUTES = "auto_close_minutes";
    private static final String KEY_AUTO_REFRESH = "auto_refresh_interval";
    private static final String KEY_ASR_ENABLED = "asr_enabled";
    private static final String KEY_SERVER_CONNECT = "server_connect_enabled";
    private static final String KEY_SYNC_QUERY = "sync_query_enabled";
    private static final String KEY_SYNC_TIMEOUT = "sync_timeout_enabled";
    private static final String KEY_SYNC_SETTINGS = "sync_settings_enabled";
    private static final String KEY_TTS_VOICE = "tts_voice";
    private static final String KEY_TTS_STYLE = "tts_style";
    private static final String KEY_TTS_CUSTOM_STYLE = "tts_custom_style";
    private static final String KEY_TTS_SPEED = "tts_speed";
    private static final String KEY_TTS_ENABLED = "tts_enabled";
    private static final String KEY_LOGS_ENABLED = "logs_enabled";
    private static final String KEY_TIMEOUT_MARK_DAYS = "timeout_mark_days";
    private static final String KEY_TIMEOUT_MARK_ENABLED = "timeout_mark_enabled";
    private static final String KEY_UI_FONT_SCALE = "ui_font_scale";
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
    private Handler mainHandler;
    private boolean serverConnectEnabled = false;

    // Views - Account & System
    private TextView tvAvatar;
    private TextView tvAccountName;
    private TextView tvAccountId;
    private LinearLayout tvAccountStatus;
    private TextView tvAppVersion;

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

    // Views - 管理员密码（访问控制，与电脑端统一机制）
    private EditText etAdminCurPwd;
    private EditText etAdminNewPwd;
    private EditText etAdminConfirmPwd;
    private Button btnSaveAdminPwd;

    // Views - 超时件标注
    private Spinner spinnerTimeoutMarkDays;
    private SwitchCompat switchTimeoutMarkEnabled;

    // Views - 界面显示（字号）
    private Spinner spinnerUiFontScale;

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

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_settings, container, false);

        // Account views
        tvAvatar = view.findViewById(R.id.tv_avatar);
        tvAccountName = view.findViewById(R.id.tv_account_name);
        tvAccountId = view.findViewById(R.id.tv_account_id);
        tvAccountStatus = view.findViewById(R.id.tv_account_status);
        tvAppVersion = view.findViewById(R.id.tv_app_version);
        // 应用版本：动态读取真实 versionName，避免与 build.gradle 中版本号不同步
        try {
            String ver = requireContext().getPackageManager()
                    .getPackageInfo(requireContext().getPackageName(), 0).versionName;
            if (tvAppVersion != null && ver != null && ver.length() > 0) {
                tvAppVersion.setText(ver);
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

        // 管理员密码（访问控制）
        etAdminCurPwd = view.findViewById(R.id.et_admin_cur_pwd);
        etAdminNewPwd = view.findViewById(R.id.et_admin_new_pwd);
        etAdminConfirmPwd = view.findViewById(R.id.et_admin_confirm_pwd);
        btnSaveAdminPwd = view.findViewById(R.id.btn_save_admin_pwd);
        if (btnSaveAdminPwd != null) btnSaveAdminPwd.setOnClickListener(v -> saveAdminPwd());

        // 显示超时件标注总开关
        switchTimeoutMarkEnabled = view.findViewById(R.id.switch_timeout_mark_enabled);
        if (switchTimeoutMarkEnabled != null) {
            switchTimeoutMarkEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isLoadingSettings) return;
                savePref(KEY_TIMEOUT_MARK_ENABLED, isChecked);
                postSettings("timeoutMarkEnabled", isChecked);
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
                    savePref(KEY_TIMEOUT_MARK_DAYS, days);
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
                    savePref(KEY_UI_FONT_SCALE, scale);
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
                savePref(KEY_ASR_ENABLED, isChecked);
                postSettings("asrEnabled", isChecked);
            });
        }

        // ---- TTS toggle ---- //
        if (switchTtsEnabled != null) {
            switchTtsEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isLoadingSettings) return;
                savePref(KEY_TTS_ENABLED, isChecked);
                postSettings("ttsEnabled", isChecked);
                if (!isChecked && ttsHelper != null) ttsHelper.stop();
            });
        }

        // ---- Logs toggle ---- //
        if (switchLogsEnabled != null) {
            switchLogsEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isLoadingSettings) return;
                savePref(KEY_LOGS_ENABLED, isChecked);
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
                savePref(KEY_SERVER_CONNECT, isChecked);
                updateSyncSubSwitches(isChecked);
                if (isChecked) {
                    loadAccountInfo();
                }
            });
        }

        // ---- Sync sub-switches ---- //
        if (switchSyncQuery != null) {
            switchSyncQuery.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isLoadingSettings) return;
                savePref(KEY_SYNC_QUERY, isChecked);
            });
        }
        if (switchSyncTimeout != null) {
            switchSyncTimeout.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isLoadingSettings) return;
                savePref(KEY_SYNC_TIMEOUT, isChecked);
            });
        }
        if (switchSyncSettings != null) {
            switchSyncSettings.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isLoadingSettings) return;
                savePref(KEY_SYNC_SETTINGS, isChecked);
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
                    savePref(KEY_TTS_VOICE, pos);
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
                    savePref(KEY_TTS_STYLE, pos);
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
                    if (fromUser) savePref(KEY_TTS_SPEED, (int)(speed * 10));
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
                SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                savedSeconds = prefs.getInt(KEY_AUTO_REFRESH, 0);
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
                    savePref(KEY_AUTO_REFRESH, seconds);
                    postSettings("autoRefreshInterval", seconds);
                }
                @Override public void onNothingSelected(AdapterView<?> parent) {}
            });
        }

        // ---- Mimo API Key ---- //
        String savedKey = "";
        try {
            SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
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
                    SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
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

        // Load saved preferences
        loadLocalPrefs();

        // Load data from server only when server connection is enabled
        if (serverConnectEnabled) {
            loadAccountInfo();
            loadSettings();
        } else {
            isLoadingSettings = false;
        }

        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (isAdded() && isViewReady && serverConnectEnabled) {
            loadAccountInfo();
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
        tvAppVersion = null;
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
        etAdminCurPwd = null;
        etAdminNewPwd = null;
        etAdminConfirmPwd = null;
        btnSaveAdminPwd = null;
        spinnerTimeoutMarkDays = null;
        switchTimeoutMarkEnabled = null;
        spinnerUiFontScale = null;
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

    // ===== Local Prefs =====

    private void savePref(String key, boolean value) {
        try {
            SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().putBoolean(key, value).commit();
        } catch (Exception ignore) {}
    }

    private void savePref(String key, int value) {
        try {
            SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().putInt(key, value).commit();
        } catch (Exception ignore) {}
    }

    private void savePref(String key, String value) {
        try {
            SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().putString(key, value).commit();
        } catch (Exception ignore) {}
    }

    private void savePref(String key, float value) {
        try {
            SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().putFloat(key, value).commit();
        } catch (Exception ignore) {}
    }

    private void loadLocalPrefs() {
        try {
            SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

            // Server connect
            serverConnectEnabled = prefs.getBoolean(KEY_SERVER_CONNECT, false);
            if (switchServerConnect != null) switchServerConnect.setChecked(serverConnectEnabled);
            updateSyncSubSwitches(serverConnectEnabled);

            // Sync sub-switches
            if (switchSyncQuery != null) switchSyncQuery.setChecked(prefs.getBoolean(KEY_SYNC_QUERY, true));
            if (switchSyncTimeout != null) switchSyncTimeout.setChecked(prefs.getBoolean(KEY_SYNC_TIMEOUT, true));
            if (switchSyncSettings != null) switchSyncSettings.setChecked(prefs.getBoolean(KEY_SYNC_SETTINGS, true));

            // ASR / TTS / Logs 开关
            if (switchAsrEnabled != null) switchAsrEnabled.setChecked(prefs.getBoolean(KEY_ASR_ENABLED, true));
            if (switchTtsEnabled != null) switchTtsEnabled.setChecked(prefs.getBoolean(KEY_TTS_ENABLED, true));
            if (switchLogsEnabled != null) switchLogsEnabled.setChecked(prefs.getBoolean(KEY_LOGS_ENABLED, true));

            // TTS voice/spinner
            if (spinnerTtsVoice != null) spinnerTtsVoice.setSelection(prefs.getInt(KEY_TTS_VOICE, 0));
            if (spinnerTtsStyle != null) spinnerTtsStyle.setSelection(prefs.getInt(KEY_TTS_STYLE, 0));
            if (etTtsCustomStyle != null) etTtsCustomStyle.setText(prefs.getString(KEY_TTS_CUSTOM_STYLE, ""));

            // TTS speed
            int speedVal = prefs.getInt(KEY_TTS_SPEED, 10);
            if (seekTtsSpeed != null) seekTtsSpeed.setProgress(speedVal - 5);
            if (tvTtsSpeedLabel != null) tvTtsSpeedLabel.setText(String.format("%.1f", speedVal / 10.0f));

            // Auto close minutes（以字符串存储，兼容 getSavedAutoCloseMinutes）
            double closeMin = 0.5;
            try {
                String closeStr = prefs.getString(KEY_AUTO_CLOSE_MINUTES, null);
                if (closeStr != null) closeMin = Double.parseDouble(closeStr);
            } catch (Exception ignore) {}
            applyAutoCloseMinutes(closeMin);

            // Auto refresh interval
            int refreshSec = prefs.getInt(KEY_AUTO_REFRESH, 0);
            applyAutoRefreshInterval(refreshSec);

            // 超时件标注天数（1~20，默认3）
            if (spinnerTimeoutMarkDays != null) {
                int days = prefs.getInt(KEY_TIMEOUT_MARK_DAYS, 3);
                if (days < 1) days = 1;
                if (days > 20) days = 20;
                spinnerTimeoutMarkDays.setSelection(days - 1);
            }
            // 显示超时件标注总开关（默认开启）
            if (switchTimeoutMarkEnabled != null) {
                switchTimeoutMarkEnabled.setChecked(prefs.getBoolean(KEY_TIMEOUT_MARK_ENABLED, true));
            }

            // 界面字号（小/中/大/特大）
            if (spinnerUiFontScale != null) {
                float scale = prefs.getFloat(KEY_UI_FONT_SCALE, 1f);
                int best = 1;
                float bestDiff = Float.MAX_VALUE;
                for (int i = 0; i < UI_FONT_SCALE_VALUES.length; i++) {
                    float diff = Math.abs(UI_FONT_SCALE_VALUES[i] - scale);
                    if (diff < bestDiff) { bestDiff = diff; best = i; }
                }
                spinnerUiFontScale.setSelection(best);
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

    // ===== Helpers =====

    private void safeToast(String msg) {
        if (!isAdded() || !isViewReady) return;
        Context ctx = getContext();
        if (ctx == null) return;
        try { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show(); } catch (Exception ignore) {}
    }

    private void saveAutoCloseMinutes(double value) {
        savePref(KEY_AUTO_CLOSE_MINUTES, String.valueOf(value));
    }

    private double getSavedAutoCloseMinutes() {
        try {
            SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String v = prefs.getString(KEY_AUTO_CLOSE_MINUTES, null);
            if (v != null) return Double.parseDouble(v);
        } catch (Exception ignore) {}
        return 1;
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

            savePref(KEY_TTS_VOICE, voicePos);
            savePref(KEY_TTS_STYLE, stylePos);
            savePref(KEY_TTS_CUSTOM_STYLE, customStyle);
            savePref(KEY_TTS_SPEED, (int)(speed * 10));

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

    // ===== 管理员密码（访问控制） =====

    private void saveAdminPwd() {
        if (!isViewReady) return;
        String curPwd = etAdminCurPwd != null ? etAdminCurPwd.getText().toString() : "";
        String newPwd = etAdminNewPwd != null ? etAdminNewPwd.getText().toString() : "";
        String confirmPwd = etAdminConfirmPwd != null ? etAdminConfirmPwd.getText().toString() : "";

        if (newPwd.isEmpty()) { safeToast("请输入新密码"); return; }
        if (newPwd.length() < 4) { safeToast("新密码至少 4 位"); return; }
        if (!newPwd.equals(confirmPwd)) { safeToast("两次输入的新密码不一致"); return; }
        if (!AdminGate.verify(requireContext(), curPwd)) { safeToast("当前密码错误"); return; }

        if (AdminGate.changePassword(requireContext(), curPwd, newPwd)) {
            try {
                LogRecorder.info(requireContext(), "SETTINGS", "管理员密码已修改", "修改成功（哈希存储，不保存明文）");
            } catch (Exception ignore) {}
            if (etAdminNewPwd != null) etAdminNewPwd.setText("");
            if (etAdminConfirmPwd != null) etAdminConfirmPwd.setText("");
            safeToast("管理员密码已修改");
        } else {
            safeToast("修改失败，请重试");
        }
    }

    private void doLogin() {
        if (!isViewReady || btnLogin == null) return;
        Log.d(TAG, "独立执行登录（不依赖电脑端）");
        try { LogRecorder.info(requireContext(), "Settings", "独立执行登录", "不依赖电脑端"); } catch (Exception ignore) {}
        try { btnLogin.setEnabled(false); btnLogin.setText("登录中..."); } catch (Exception ignore) {}

        new Thread(() -> {
            boolean success = false;
            String errMsg = "未知错误";
            String userId = null;
            String accountName = null;
            try {
                DirectApiClient directClient = new DirectApiClient(requireContext());
                // 通过反射调用 private doLogin() 方法；或者直接 ensureLogin 内部会触发登录
                java.lang.reflect.Method m = DirectApiClient.class.getDeclaredMethod("ensureLogin");
                m.setAccessible(true);
                JSONObject authResult = (JSONObject) m.invoke(directClient);
                userId = authResult == null ? "" : authResult.optString("userId", "");
                success = authResult != null && userId.length() > 0;
                if (!success) errMsg = "登录失败，userId为空";
                else {
                    // Save auth token locally
                    try {
                        String accessToken = authResult.optString("accessToken", "");
                        SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                        prefs.edit()
                                .putString("local_access_token", accessToken)
                                .putString("local_user_id", userId)
                                .putLong("local_token_expires", System.currentTimeMillis() + 24 * 60 * 60 * 1000L)
                                .apply();
                    } catch (Exception ignore) {}
                    accountName = "查件助手";
                }
            } catch (Exception e) {
                errMsg = e.getMessage() == null ? "登录异常" : e.getMessage();
                Log.w(TAG, "独立登录出错: " + errMsg);
                try { LogRecorder.error(requireContext(), "Settings", "独立登录出错", errMsg); } catch (Exception ignore) {}
            }

            final boolean finalOk = success;
            final String finalErr = errMsg;
            final String finalUserId = userId;
            final String finalAccountName = accountName;
            mainHandler.post(() -> {
                if (!isViewReady) return;
                if (btnLogin != null) {
                    try { btnLogin.setEnabled(true); btnLogin.setText("重新登录"); } catch (Exception ignore) {}
                }
                if (finalOk) {
                    // 更新 UI 账号信息显示
                    if (tvAccountId != null && finalUserId.length() > 0) {
                        try { tvAccountId.setText("ID: " + finalUserId); } catch (Exception ignore) {}
                    }
                    if (tvAccountName != null && finalAccountName != null) {
                        try { tvAccountName.setText(finalAccountName); } catch (Exception ignore) {}
                    }
                    if (tvAccountStatus != null) {
                        try {
                            tvAccountStatus.setBackgroundResource(R.drawable.bg_status_pending);
                            int childCount = tvAccountStatus.getChildCount();
                            for (int i = 0; i < childCount; i++) {
                                View child = tvAccountStatus.getChildAt(i);
                                if (child instanceof TextView) {
                                    ((TextView) child).setTextColor(getResources().getColor(R.color.accent, requireContext().getTheme()));
                                    ((TextView) child).setText("已登录");
                                }
                            }
                        } catch (Exception ignore) {}
                    }
                    // 更新连接状态
                    updateConnectionStatus(true);
                    safeToast("登录成功");
                } else {
                    updateConnectionStatus(false);
                    safeToast("登录失败: " + finalErr);
                }
            });
        }, "direct-login").start();
    }
}
