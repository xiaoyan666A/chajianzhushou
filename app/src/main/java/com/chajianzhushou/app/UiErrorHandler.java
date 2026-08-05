package com.chajianzhushou.app;

import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

/**
 * 统一网络/业务错误处理：
 * - 登录失效类错误 → 清除本机凭据与 token，跳转登录界面；
 * - 其余错误由调用方按原逻辑提示。
 */
public final class UiErrorHandler {

    private UiErrorHandler() {}

    /** 判断错误消息是否属于"登录失效"（需重新登录） */
    public static boolean isLoginExpired(String msg) {
        if (msg == null) return false;
        return msg.contains("登录已失效")
                || msg.contains("登录失效")
                || msg.contains("未登录")
                || msg.contains("登录过期")
                || msg.contains("登录信息无效")
                || msg.contains("SN-TOKEN-INEFFECTIVE")
                || msg.contains("token 过期")
                || msg.contains("token过期");
    }

    /** 登录失效：清除凭据与 token，提示并跳转登录界面 */
    public static void onLoginExpired(Context ctx) {
        if (ctx == null) return;
        Context app = ctx.getApplicationContext();
        try { new LoginStore(app).clearAll(); } catch (Exception ignore) {}
        try { Toast.makeText(app, "登录已失效，请重新登录", Toast.LENGTH_SHORT).show(); } catch (Exception ignore) {}
        try {
            Intent i = new Intent(app, LoginActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            app.startActivity(i);
        } catch (Exception ignore) {}
    }

    /** 统一错误入口：登录失效自动跳登录页；其余错误不处理（由调用方提示） */
    public static void handle(Context ctx, String msg) {
        if (isLoginExpired(msg)) {
            onLoginExpired(ctx);
        }
    }
}
