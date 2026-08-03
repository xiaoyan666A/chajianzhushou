package com.chajianzhushou.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.appcompat.app.AppCompatDelegate;

import java.util.Calendar;

/**
 * 界面风格主题管理器（与电脑端一致的三模式：浅色/深色/自动切换）
 *
 * 映射约定（重要）：
 *   - 本应用默认配色即"深色"，存放在 values/（白天模式资源）；
 *   - "浅色"配色存放在 values-night/（夜间模式资源）。
 *   因此：深色模式 → MODE_NIGHT_NO（使用 values/），浅色模式 → MODE_NIGHT_YES（使用 values-night/），
 *   自动模式 → 按日出日落时间动态选择上述两者。
 *
 * 持久化：SharedPreferences（chajianzhushou_prefs），字段与电脑端 /api/settings 的
 * themeMode / sunriseTime / sunsetTime 一一对应，可双向同步。
 */
public class ThemeManager {
    private static final String TAG = "ThemeManager";
    public static final String PREFS_NAME = "chajianzhushou_prefs";

    public static final String KEY_THEME_MODE = "theme_mode";
    public static final String KEY_SUNRISE_TIME = "sunrise_time";
    public static final String KEY_SUNSET_TIME = "sunset_time";
    private static final String KEY_LAST_LAT = "theme_last_lat";
    private static final String KEY_LAST_LNG = "theme_last_lng";

    public static final String MODE_LIGHT = "light";
    public static final String MODE_DARK = "dark";
    public static final String MODE_AUTO = "auto";
    public static final String DEFAULT_SUNRISE = "06:00";
    public static final String DEFAULT_SUNSET = "18:00";

    private static volatile long lastApplyTime = 0;
    private static volatile String lastAppliedMode = null;

    private ThemeManager() {}

    public static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static String getMode(Context ctx) {
        try {
            return prefs(ctx).getString(KEY_THEME_MODE, MODE_DARK);
        } catch (Exception e) { return MODE_DARK; }
    }

    public static String getSunrise(Context ctx) {
        try {
            return prefs(ctx).getString(KEY_SUNRISE_TIME, DEFAULT_SUNRISE);
        } catch (Exception e) { return DEFAULT_SUNRISE; }
    }

    public static String getSunset(Context ctx) {
        try {
            return prefs(ctx).getString(KEY_SUNSET_TIME, DEFAULT_SUNSET);
        } catch (Exception e) { return DEFAULT_SUNSET; }
    }

    /** 保存模式并立即生效（实时切换，无需重启）。 */
    public static void setMode(Context ctx, String mode) {
        if (mode == null || !(MODE_LIGHT.equals(mode) || MODE_DARK.equals(mode) || MODE_AUTO.equals(mode))) return;
        prefs(ctx).edit().putString(KEY_THEME_MODE, mode).apply();
        apply(ctx);
    }

    /** 保存日出/日落时间（HH:mm），自动模式下立即重新计算。 */
    public static void setSunTimes(Context ctx, String sunrise, String sunset) {
        prefs(ctx).edit()
                .putString(KEY_SUNRISE_TIME, normalizeTime(sunrise, DEFAULT_SUNRISE))
                .putString(KEY_SUNSET_TIME, normalizeTime(sunset, DEFAULT_SUNSET))
                .apply();
        apply(ctx);
    }

    public static String normalizeTime(String t, String def) {
        if (t != null && t.matches("^\\d{1,2}:\\d{2}$")) {
            String[] p = t.split(":");
            int h = Integer.parseInt(p[0]) % 24;
            int m = Integer.parseInt(p[1]);
            if (m >= 0 && m < 60) {
                return String.format(java.util.Locale.US, "%02d:%02d", h, m);
            }
        }
        return def;
    }

    /**
     * 根据保存的模式把 AppCompatDelegate 切到对应资源集合。
     *
     * 资源映射（本应用约定）：深色配色在 values/，浅色配色在 values-night/，
     * 因此 浅色=MODE_NIGHT_YES（values-night）、深色=MODE_NIGHT_NO（values/）。
     * 自动模式：白天（日出~日落之间）→ 浅色（MODE_NIGHT_YES），夜间 → 深色（MODE_NIGHT_NO）。
     */
    public static void apply(Context ctx) {
        String mode = getMode(ctx);
        int nightMode;
        if (MODE_AUTO.equals(mode)) {
            nightMode = isLightNow(ctx) ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO;
        } else if (MODE_LIGHT.equals(mode)) {
            nightMode = AppCompatDelegate.MODE_NIGHT_YES;
        } else {
            nightMode = AppCompatDelegate.MODE_NIGHT_NO;
        }
        // 限流：1.5 秒内不重复执行，避免自动模式频繁触发重建
        long now = System.currentTimeMillis();
        if (now - lastApplyTime < 1500 && mode.equals(lastAppliedMode)) return;
        lastApplyTime = now;
        lastAppliedMode = mode;
        try {
            AppCompatDelegate.setDefaultNightMode(nightMode);
            Log.d(TAG, "apply theme mode=" + mode + " -> nightMode=" + nightMode);
        } catch (Throwable t) {
            Log.w(TAG, "apply theme fail: " + t.getMessage());
        }
    }

    /**
     * 自动模式：当前时刻是否为"浅色"（白天）。
     * 支持跨午夜的时间段（如日出 22:00、日落 04:00）。
     */
    public static boolean isLightNow(Context ctx) {
        int nowMin = minutesOfDay(Calendar.getInstance());
        int sMin = minutesOfTime(getSunrise(ctx));
        int eMin = minutesOfTime(getSunset(ctx));
        if (sMin <= eMin) return nowMin >= sMin && nowMin < eMin;
        return nowMin >= sMin || nowMin < eMin;
    }

    private static int minutesOfTime(String hhmm) {
        try {
            String[] p = hhmm.split(":");
            return (Integer.parseInt(p[0]) % 24) * 60 + Integer.parseInt(p[1]);
        } catch (Exception e) { return 6 * 60; }
    }

    private static int minutesOfDay(Calendar c) {
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
    }

    /** NOAA 日出/日落计算：返回 String[] {sunrise, sunset}（当地时间 HH:mm），极昼/极夜返回 null。 */
    public static String[] calcSunTimes(Calendar date, double lat, double lng) {
        try {
            final double rad = Math.PI / 180.0;
            // 当前时刻的儒略日（UTC）
            double jd = date.getTimeInMillis() / 86400000.0 + 2440587.5;
            double N = Math.ceil(jd - 2451545.0 + 0.0008);
            double Jstar = N - lng / 360.0;
            double M = ((357.5291 + 0.98560028 * Jstar) % 360) * rad;
            double C = 1.9148 * Math.sin(M) + 0.0200 * Math.sin(2 * M) + 0.0003 * Math.sin(3 * M);
            double lambda = ((M / rad + C + 180 + 102.9372) % 360) * rad;
            double Jtransit = 2451545.0 + Jstar + 0.0053 * Math.sin(M) - 0.0069 * Math.sin(2 * lambda);
            double sinDec = Math.sin(lambda) * Math.sin(23.4397 * rad);
            double cosDec = Math.cos(Math.asin(sinDec));
            double cosH = (Math.sin(-0.833 * rad) - Math.sin(lat * rad) * sinDec) / (Math.cos(lat * rad) * cosDec);
            if (cosH < -1.0 || cosH > 1.0) return null; // 极昼/极夜
            double H = Math.acos(cosH) / rad;
            double Jset = 2451545.0 + Jstar + 0.0053 * Math.sin(M) - 0.0069 * Math.sin(2 * lambda) + H / 360.0;
            double Jrise = Jtransit - (Jset - Jtransit);
            String sunrise = fmtLocal(Jrise);
            String sunset = fmtLocal(Jset);
            return new String[]{sunrise, sunset};
        } catch (Exception e) {
            return null;
        }
    }

    /** 儒略日（UTC）→ 本地时区 HH:mm */
    private static String fmtLocal(double jd) {
        long ms = Math.round((jd - 2440587.5) * 86400000.0);
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        return String.format(java.util.Locale.US, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
    }

    /** 使用上次定位坐标重新计算日出日落并保存（供启动时恢复自动模式）。 */
    public static boolean refreshSunTimesFromCachedLocation(Context ctx) {
        SharedPreferences p = prefs(ctx);
        if (!p.contains(KEY_LAST_LAT) || !p.contains(KEY_LAST_LNG)) return false;
        try {
            double lat = p.getFloat(KEY_LAST_LAT, 0f);
            double lng = p.getFloat(KEY_LAST_LNG, 0f);
            if (lat == 0 && lng == 0) return false;
            String[] t = calcSunTimes(Calendar.getInstance(), lat, lng);
            if (t == null) return false;
            setSunTimes(ctx, t[0], t[1]);
            return true;
        } catch (Exception e) { return false; }
    }

    /** 缓存定位坐标。 */
    public static void cacheLocation(Context ctx, double lat, double lng) {
        prefs(ctx).edit().putFloat(KEY_LAST_LAT, (float) lat).putFloat(KEY_LAST_LNG, (float) lng).apply();
    }

    public interface LocationCallback {
        void onSuccess(double lat, double lng);
        void onError(String message);
    }

    /**
     * 请求单次定位（使用系统 LocationManager，无需额外依赖）。
     * 有缓存定位时优先用缓存；否则请求粗/精定位更新。需先在 Manifest 声明定位权限。
     */
    @SuppressLint("MissingPermission")
    public static void requestSingleLocation(Context ctx, final LocationCallback callback) {
        if (callback == null) return;
        try {
            final LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) { callback.onError("无定位服务"); return; }

            boolean fine = ctx.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
            boolean coarse = ctx.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
            if (!fine && !coarse) { callback.onError("未授权定位权限"); return; }

            Location best = null;
            // 先尝试取缓存定位
            try {
                if (fine) best = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                if (best == null && coarse) best = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                if (best == null) best = lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER);
            } catch (Exception ignore) {}

            final Location cached = best;
            if (cached != null && cached.getLatitude() != 0 && cached.getLongitude() != 0) {
                cacheLocation(ctx, cached.getLatitude(), cached.getLongitude());
                callback.onSuccess(cached.getLatitude(), cached.getLongitude());
                return;
            }

            // 无缓存 → 单次请求（多个 Provider 都试试）
            final boolean[] done = {false};
            final Handler h = new Handler(Looper.getMainLooper());
            final android.location.LocationListener[] listenerHolder = new android.location.LocationListener[1];
            final Runnable timeout = () -> {
                if (!done[0]) {
                    done[0] = true;
                    try { if (listenerHolder[0] != null) lm.removeUpdates(listenerHolder[0]); } catch (Exception ignore) {}
                    callback.onError("定位超时或不可用");
                }
            };
            final android.location.LocationListener locListener = new android.location.LocationListener() {
                @Override public void onLocationChanged(Location location) {
                    if (done[0]) return;
                    if (location == null || (location.getLatitude() == 0 && location.getLongitude() == 0)) return;
                    done[0] = true;
                    try { lm.removeUpdates(this); } catch (Exception ignore) {}
                    h.removeCallbacks(timeout);
                    cacheLocation(ctx, location.getLatitude(), location.getLongitude());
                    callback.onSuccess(location.getLatitude(), location.getLongitude());
                }
                @Override public void onStatusChanged(String provider, int status, android.os.Bundle extras) {}
                @Override public void onProviderEnabled(String provider) {}
                @Override public void onProviderDisabled(String provider) {}
            };
            listenerHolder[0] = locListener;
            h.postDelayed(timeout, 10000);
            try {
                if (fine) lm.requestSingleUpdate(LocationManager.GPS_PROVIDER, locListener, Looper.getMainLooper());
                if (coarse) lm.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, locListener, Looper.getMainLooper());
            } catch (Exception e) {
                h.removeCallbacks(timeout);
                callback.onError("请求定位失败: " + e.getMessage());
            }
        } catch (Exception e) {
            callback.onError("定位异常: " + e.getMessage());
        }
    }
}
