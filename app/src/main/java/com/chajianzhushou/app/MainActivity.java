package com.chajianzhushou.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import org.json.JSONObject;

import java.util.Calendar;

public class MainActivity extends AppCompatActivity {
    /** 界面字号倍率（0=未初始化，进程启动时从设置读取；设置页修改后重建 Activity 生效） */
    static volatile float sFontScale = 0f;

    private ApiService apiService;
    private boolean tokenRefreshedToday = false;
    private int lastTokenRefreshDay = -1;
    private String currentPage = "main"; // 默认查件页

    // ===== 4 个 Fragment 实例保活（切换时只 show/hide，不销毁重建）=====
    private Fragment fragmentQuery;
    private Fragment fragmentTimeout;
    private Fragment fragmentLogs;
    private Fragment fragmentSettings;

    private static final String TAG_QUERY = "page_query";
    private static final String TAG_TIMEOUT = "page_timeout";
    private static final String TAG_LOGS = "page_logs";
    private static final String TAG_SETTINGS = "page_settings";

    private static volatile Context sAppContext;

    /** 提供 Application Context 给无 Context 的模块（DirectApiClient/SyncClient 等）写日志。 */
    public static Context getAppContext() { return sAppContext; }

    /** 供登录界面等入口在 Application Context 尚未初始化时提前设置（进程内共享） */
    public static void setAppContext(Context ctx) {
        sAppContext = ctx == null ? null : ctx.getApplicationContext();
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        // 应用界面字号设置：首次从 SharedPreferences 读取，之后由设置页更新静态值并重建 Activity
        if (sFontScale == 0f) {
            try {
                SharedPreferences prefs = newBase.getSharedPreferences("chajianzhushou_prefs", Context.MODE_PRIVATE);
                sFontScale = prefs.getFloat(SettingsStore.KEY_UI_FONT_SCALE, 1f);
            } catch (Exception e) {
                sFontScale = 1f;
            }
        }
        super.attachBaseContext(applyFontScale(newBase, sFontScale));
    }

    /** 在系统字号基础上叠加用户倍率（小/中/大/特大），并做合理钳制 */
    /** 在系统字号基础上叠加用户倍率（小/中/大/特大），并做合理钳制；登录界面等入口复用 */
    public static Context applyFontScale(Context base, float userScale) {
        try {
            Configuration cfg = new Configuration(base.getResources().getConfiguration());
            float system = base.getResources().getConfiguration().fontScale;
            cfg.fontScale = Math.max(0.8f, Math.min(1.5f, system * userScale));
            return base.createConfigurationContext(cfg);
        } catch (Throwable t) {
            return base;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 必须在 super.onCreate 之前应用已保存的界面风格，否则 Activity 会按默认主题初始化
        // 自动模式：先按缓存定位重算今天的日出日落，再应用
        ThemeManager.refreshSunTimesFromCachedLocation(this);
        ThemeManager.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        sAppContext = getApplicationContext();
        // 初始化 LogRecorder（如果用户开启日志输出开关，后续调用即写入）
        try { LogRecorder.getInstance(sAppContext); } catch (Exception ignore) {}
        LogRecorder.info(sAppContext, "Main", "APP启动", "查件助手已启动");

        // 未保存兔喜账号凭据 → 跳转登录界面（新安装 / 已退出登录 / 升级后首次）
        LoginStore loginStore = new LoginStore(this);
        if (!loginStore.hasCredentials()) {
            Intent i = new Intent(this, LoginActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(i);
            finish();
            return;
        }

        apiService = new ApiService(this);

        View btnQuery = findViewById(R.id.btn_float_query);
        View btnTimeoutSettings = findViewById(R.id.btn_float_timeout_settings);
        View btnSettings = findViewById(R.id.btn_float_settings);

        // 注意：日志页没有底部按钮，暂时保留通过其他入口；未来有需要可以加一个按钮
        btnQuery.setOnClickListener(v -> switchPage("main"));
        btnTimeoutSettings.setOnClickListener(v -> switchPage("timeout"));
        btnSettings.setOnClickListener(v -> enterSettings());

        // 屏幕旋转/进程重启时，FragmentManager 里可能已经有之前的 Fragment，先尝试按 TAG 找回
        FragmentManager fm = getSupportFragmentManager();
        if (savedInstanceState != null) {
            Fragment fq = fm.findFragmentByTag(TAG_QUERY);
            if (fq != null) fragmentQuery = fq;
            Fragment ft = fm.findFragmentByTag(TAG_TIMEOUT);
            if (ft != null) fragmentTimeout = ft;
            Fragment fl = fm.findFragmentByTag(TAG_LOGS);
            if (fl != null) fragmentLogs = fl;
            Fragment fs = fm.findFragmentByTag(TAG_SETTINGS);
            if (fs != null) fragmentSettings = fs;
        }

        if (savedInstanceState == null) {
            // 首次启动直接进入查件页（add 进去）
            switchPage("main");
        }

        checkAndRefreshToken();
    }

    /**
     * 切换页面：
     * - 第一次进入某页面时，创建 Fragment 并 add 到容器；
     * - 之后切换时只 hide 旧的 show 新的，避免重新加载/重新请求/重新渲染查件列表。
     */
    public void switchPage(@NonNull String page) {
        currentPage = page;

        FragmentManager fm = getSupportFragmentManager();
        FragmentTransaction tx = fm.beginTransaction();
        // 统一动画（可选），避免空白闪屏
        // tx.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE);

        // 1. 先 hide 已经 add 过但不是目标的所有 Fragment
        if (fragmentQuery != null && !page.equals("main")) tx.hide(fragmentQuery);
        if (fragmentTimeout != null && !page.equals("timeout")) tx.hide(fragmentTimeout);
        if (fragmentLogs != null && !page.equals("logs")) tx.hide(fragmentLogs);
        if (fragmentSettings != null && !page.equals("settings")) tx.hide(fragmentSettings);

        // 2. 按目标页面 show 或 add
        switch (page) {
            case "main": {
                if (fragmentQuery == null) {
                    fragmentQuery = new QueryFragment();
                    tx.add(R.id.fragment_container, fragmentQuery, TAG_QUERY);
                } else {
                    tx.show(fragmentQuery);
                }
                break;
            }
            case "timeout": {
                if (fragmentTimeout == null) {
                    fragmentTimeout = new TimeoutFragment();
                    tx.add(R.id.fragment_container, fragmentTimeout, TAG_TIMEOUT);
                } else {
                    tx.show(fragmentTimeout);
                }
                break;
            }
            case "logs": {
                if (fragmentLogs == null) {
                    fragmentLogs = new LogsFragment();
                    tx.add(R.id.fragment_container, fragmentLogs, TAG_LOGS);
                } else {
                    tx.show(fragmentLogs);
                }
                break;
            }
            case "settings": {
                if (fragmentSettings == null) {
                    fragmentSettings = new SettingsFragment();
                    tx.add(R.id.fragment_container, fragmentSettings, TAG_SETTINGS);
                } else {
                    tx.show(fragmentSettings);
                }
                break;
            }
            default: {
                // 未知页面，兜底：回查件
                if (fragmentQuery == null) {
                    fragmentQuery = new QueryFragment();
                    tx.add(R.id.fragment_container, fragmentQuery, TAG_QUERY);
                } else {
                    tx.show(fragmentQuery);
                }
            }
        }

        tx.commitAllowingStateLoss();
    }

    /** 进入设置页：点击直接进入 */
    private void enterSettings() {
        switchPage("settings");
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkAndRefreshToken();
    }

    private void checkAndRefreshToken() {
        Calendar calendar = Calendar.getInstance();
        int today = calendar.get(Calendar.DAY_OF_YEAR);
        if (today != lastTokenRefreshDay) {
            lastTokenRefreshDay = today;
            tokenRefreshedToday = false;
        }
        if (!tokenRefreshedToday) {
            refreshTokenSilently();
        }
    }

    private void refreshTokenSilently() {
        apiService.refreshToken(new ApiService.ApiCallback() {
            @Override
            public void onSuccess(JSONObject response) {
                tokenRefreshedToday = true;
            }
            @Override
            public void onError(String error) {}
        });
    }
}
