package com.chajianzhushou.app;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 设置存储层：集中管理 SettingsFragment 的全部 SharedPreferences key 与读写，
 * 避免 key 字符串与 prefs 样板散落在界面代码中。
 */
public class SettingsStore {

    public static final String PREF_NAME = "chajianzhushou_prefs";

    public static final String KEY_AUTO_CLOSE_MINUTES = "auto_close_minutes";
    public static final String KEY_AUTO_REFRESH = "auto_refresh_interval";
    public static final String KEY_AUTO_REFRESH_MAX = "auto_refresh_max_count";
    public static final String KEY_ASR_ENABLED = "asr_enabled";
    public static final String KEY_SERVER_CONNECT = "server_connect_enabled";
    public static final String KEY_SYNC_QUERY = "sync_query_enabled";
    public static final String KEY_SYNC_TIMEOUT = "sync_timeout_enabled";
    public static final String KEY_SYNC_SETTINGS = "sync_settings_enabled";
    public static final String KEY_TTS_VOICE = "tts_voice";
    public static final String KEY_TTS_STYLE = "tts_style";
    public static final String KEY_TTS_CUSTOM_STYLE = "tts_custom_style";
    public static final String KEY_TTS_SPEED = "tts_speed";
    public static final String KEY_TTS_ENABLED = "tts_enabled";
    public static final String KEY_LOGS_ENABLED = "logs_enabled";
    public static final String KEY_TIMEOUT_MARK_DAYS = "timeout_mark_days";
    public static final String KEY_TIMEOUT_MARK_ENABLED = "timeout_mark_enabled";
    public static final String KEY_UI_FONT_SCALE = "ui_font_scale";
    public static final String KEY_GRID_MANUAL_ENABLED = "grid_manual_columns_enabled";
    public static final String KEY_GRID_MANUAL_COLUMNS_PORTRAIT = "grid_manual_columns_portrait";
    public static final String KEY_GRID_MANUAL_COLUMNS_LANDSCAPE = "grid_manual_columns_landscape";
    public static final String KEY_PIC_OUTBOUND_ENABLED = "pic_outbound_enabled";
    public static final String KEY_MULTI_TAIL_ENABLED = "multi_tail_enabled";
    public static final String KEY_IMAGE_CACHE_DAYS = "image_cache_days";
    /** 图片缓存过期时长（小时），新版存储单位；旧版按天存于 KEY_IMAGE_CACHE_DAYS */
    public static final String KEY_IMAGE_CACHE_EXPIRE_HOURS = "image_cache_expire_hours";
    public static final String KEY_LOG_RETAIN_DAYS = "log_retain_days";
    public static final String KEY_MIMO_API_KEY = "mimo_api_key";
    public static final String KEY_MIMO_API_KEY_LOCKED = "mimo_api_key_locked";
    // 功能区总开关（默认开启；关闭后禁用该卡片内配置）
    public static final String KEY_UI_DISPLAY_ENABLED = "ui_display_enabled";
    public static final String KEY_CACHE_MGMT_ENABLED = "cache_mgmt_enabled";
    public static final String KEY_THEME_ENABLED = "theme_enabled";
    // 进阶功能（输入解锁码 admin 后显示语音识别/TTS/日志/服务器连接卡片）
    public static final String KEY_ADVANCED_FEATURES_ENABLED = "advanced_features_enabled";

    private final SharedPreferences prefs;

    public SettingsStore(Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    /** 供读取原始值（个别场景直接操作 prefs） */
    public SharedPreferences prefs() {
        return prefs;
    }

    public void set(String key, boolean value) { prefs.edit().putBoolean(key, value).apply(); }
    public void set(String key, int value) { prefs.edit().putInt(key, value).apply(); }
    public void set(String key, String value) { prefs.edit().putString(key, value).apply(); }
    public void set(String key, float value) { prefs.edit().putFloat(key, value).apply(); }

    public boolean getBoolean(String key, boolean def) { return prefs.getBoolean(key, def); }
    public int getInt(String key, int def) { return prefs.getInt(key, def); }
    public String getString(String key, String def) { return prefs.getString(key, def); }
    public float getFloat(String key, float def) { return prefs.getFloat(key, def); }

    /** 自动关闭分钟数以字符串存储（兼容小数），读取时解析 */
    public double getDoubleMinutes(String key, double def) {
        String s = prefs.getString(key, "");
        try {
            return Double.parseDouble(s);
        } catch (Exception e) {
            return def;
        }
    }

    public void setMinutes(String key, double value) {
        prefs.edit().putString(key, String.valueOf(value)).apply();
    }
}
