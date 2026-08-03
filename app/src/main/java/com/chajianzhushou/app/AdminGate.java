package com.chajianzhushou.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.MessageDigest;

/**
 * 管理员密码访问控制（与电脑端统一机制）：
 * - 进入设置页前需验证管理员密码（默认 888888，SHA-256 哈希存储，不存明文）；
 * - 验证通过后授予 30 分钟访问会话，期间再进设置无需验证；
 * - 会话过期后再次进入需重新验证。
 */
public class AdminGate {
    /** 验证通过后的访问有效期：30 分钟 */
    public static final long SESSION_MS = 30L * 60 * 1000;
    public static final String DEFAULT_PWD = "888888";

    private static final String PREF_NAME = "chajianzhushou_prefs";
    private static final String KEY_HASH = "admin_pwd_hash";
    private static final String KEY_EXPIRE = "admin_pwd_expire";

    private AdminGate() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    private static String getStoredHash(Context ctx) {
        String h = prefs(ctx).getString(KEY_HASH, null);
        if (h == null || h.isEmpty()) {
            // 首次使用：写入默认密码哈希
            h = hash(DEFAULT_PWD);
            prefs(ctx).edit().putString(KEY_HASH, h).apply();
        }
        return h;
    }

    /** 当前 30 分钟会话是否有效（有效则进入设置页无需验证） */
    public static boolean isSessionValid(Context ctx) {
        try {
            return prefs(ctx).getLong(KEY_EXPIRE, 0L) > System.currentTimeMillis();
        } catch (Exception e) { return false; }
    }

    /** 校验密码：输入与存储哈希一致则通过 */
    public static boolean verify(Context ctx, String pwd) {
        if (pwd == null) return false;
        String h = hash(pwd);
        return h != null && h.equals(getStoredHash(ctx));
    }

    /** 验证通过后授予 30 分钟访问会话 */
    public static void grant(Context ctx) {
        prefs(ctx).edit().putLong(KEY_EXPIRE, System.currentTimeMillis() + SESSION_MS).apply();
    }

    /** 修改密码：需校验当前密码；成功返回 true */
    public static boolean changePassword(Context ctx, String curPwd, String newPwd) {
        if (newPwd == null || newPwd.length() < 4) return false;
        if (!verify(ctx, curPwd)) return false;
        String h = hash(newPwd);
        if (h == null) return false;
        prefs(ctx).edit().putString(KEY_HASH, h).apply();
        return true;
    }

    /** SHA-256 hex */
    public static String hash(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b & 0xFF));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
