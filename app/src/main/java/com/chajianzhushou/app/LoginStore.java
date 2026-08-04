package com.chajianzhushou.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

/**
 * 登录凭据与 token 缓存存储层。
 * - 凭据：手机号 + 密码（明文存储，用户已确认；AndroidManifest allowBackup=false 兜底）
 * - token 缓存：沿用设置页旧的 local_* key，向后兼容
 */
public class LoginStore {

    public static final String PREF_NAME = "chajianzhushou_prefs";

    // 凭据
    public static final String KEY_USERNAME = "login_username";
    public static final String KEY_PASSWORD = "login_password";

    // token 缓存（local_* 为设置页旧 key，继续复用）
    public static final String KEY_ACCESS_TOKEN = "local_access_token";
    public static final String KEY_USER_ID = "local_user_id";
    public static final String KEY_TOKEN_EXPIRES = "local_token_expires";
    public static final String KEY_REFRESH_TOKEN = "local_refresh_token";
    public static final String KEY_UNION_ID = "local_union_id";
    public static final String KEY_YS_DT = "local_ys_dt";
    public static final String KEY_STAFF_CODE = "local_staff_code";

    /** token 有效时长：24 小时（与服务器无明确过期时间时保持一致） */
    public static final long TOKEN_TTL_MS = 24 * 60 * 60 * 1000L;

    private final SharedPreferences prefs;

    public LoginStore(Context context) {
        Context appCtx = context == null ? MainActivity.getAppContext() : context.getApplicationContext();
        this.prefs = appCtx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    // ===== 凭据 =====

    public void saveCredentials(String username, String password) {
        prefs.edit()
                .putString(KEY_USERNAME, username == null ? "" : username.trim())
                .putString(KEY_PASSWORD, password == null ? "" : password)
                .apply();
    }

    public String getUsername() {
        return prefs.getString(KEY_USERNAME, "");
    }

    public String getPassword() {
        return prefs.getString(KEY_PASSWORD, "");
    }

    public boolean hasCredentials() {
        // 手机号是登录身份的核心；密码可能为空（验证码登录场景），自动重登优先走 refreshToken
        return getUsername().length() > 0;
    }

    /** 脱敏后的手机号（保留前 3 后 4），用于界面展示 */
    public String getMaskedUsername() {
        String u = getUsername();
        if (u.length() <= 7) return u;
        return u.substring(0, 3) + "****" + u.substring(u.length() - 4);
    }

    // ===== token 缓存 =====

    public void saveToken(JSONObject result, long expiresAt) {
        if (result == null) return;
        prefs.edit()
                .putString(KEY_ACCESS_TOKEN, result.optString("accessToken", ""))
                .putString(KEY_USER_ID, result.optString("userId", ""))
                .putString(KEY_REFRESH_TOKEN, result.optString("refreshToken", ""))
                .putString(KEY_UNION_ID, result.optString("unionId", ""))
                .putString(KEY_YS_DT, result.optString("ysDt", ""))
                .putString(KEY_STAFF_CODE, result.optString("staffCode", ""))
                .putLong(KEY_TOKEN_EXPIRES, expiresAt)
                .apply();
    }

    public String getAccessToken() {
        return prefs.getString(KEY_ACCESS_TOKEN, "");
    }

    public String getUserId() {
        return prefs.getString(KEY_USER_ID, "");
    }

    public String getRefreshToken() {
        return prefs.getString(KEY_REFRESH_TOKEN, "");
    }

    public String getUnionId() {
        return prefs.getString(KEY_UNION_ID, "");
    }

    public String getYsDt() {
        return prefs.getString(KEY_YS_DT, "");
    }

    public String getStaffCode() {
        return prefs.getString(KEY_STAFF_CODE, "");
    }

    public long getTokenExpiresAt() {
        return prefs.getLong(KEY_TOKEN_EXPIRES, 0L);
    }

    public boolean hasValidToken(long now) {
        String token = getAccessToken();
        return token.length() > 0 && getTokenExpiresAt() > now;
    }

    /** 仅清除 token 缓存（保留凭据，用于自动重新登录） */
    public void clearToken() {
        prefs.edit()
                .remove(KEY_ACCESS_TOKEN)
                .remove(KEY_USER_ID)
                .remove(KEY_TOKEN_EXPIRES)
                .remove(KEY_REFRESH_TOKEN)
                .remove(KEY_UNION_ID)
                .remove(KEY_YS_DT)
                .remove(KEY_STAFF_CODE)
                .apply();
    }

    /** 仅清除 accessToken 与过期时间（保留 refreshToken 等，供无感换新） */
    public void clearAccessToken() {
        prefs.edit()
                .remove(KEY_ACCESS_TOKEN)
                .remove(KEY_USER_ID)
                .remove(KEY_TOKEN_EXPIRES)
                .apply();
    }

    /** 清除全部登录数据（退出登录） */
    public void clearAll() {
        prefs.edit()
                .remove(KEY_USERNAME)
                .remove(KEY_PASSWORD)
                .remove(KEY_ACCESS_TOKEN)
                .remove(KEY_USER_ID)
                .remove(KEY_TOKEN_EXPIRES)
                .remove(KEY_REFRESH_TOKEN)
                .remove(KEY_UNION_ID)
                .remove(KEY_YS_DT)
                .remove(KEY_STAFF_CODE)
                .apply();
    }
}
