package com.chajianzhushou.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.view.animation.RotateAnimation;
import android.widget.TextView;

/**
 * 自动刷新控制器（从 QueryFragment 拆出）：
 * 负责按设置间隔调度自动查询、提前 1s 激活指示器、空闲/执行中指示器视觉切换。
 */
public class AutoRefreshController {

    public interface Host {
        boolean isViewReady();
        boolean isUserTouching();
        int getIntervalSeconds();
        /** 执行一轮自动查询（宿主实现查询条件取值与 performQuery 调用） */
        void onTick();
        /** 指示器激活状态变化（宿主可在此禁用/恢复"竖向排列"开关等） */
        void onActiveChanged(boolean active);
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable runnable;
    private Runnable preActivate;
    private Context context;
    private Host host;
    private View indicator;
    private TextView label;
    private RotateAnimation spinAnim;
    private boolean active = false;
    /** 用户手动暂停（点击"自动刷新中......"切换）；暂停期间不再调度下一轮 */
    private boolean paused = false;

    public void attach(Context context, Host host, View indicator, TextView label) {
        this.context = context;
        this.host = host;
        this.indicator = indicator;
        this.label = label;
        this.spinAnim = new RotateAnimation(0f, 360f,
                RotateAnimation.RELATIVE_TO_SELF, 0.5f,
                RotateAnimation.RELATIVE_TO_SELF, 0.5f);
        spinAnim.setDuration(800);
        spinAnim.setRepeatCount(RotateAnimation.INFINITE);
        spinAnim.setInterpolator(new LinearInterpolator());
        setActive(false);
    }

    /** 按设置间隔调度一轮自动查询；执行后是否续排由宿主（查询结果）决定 */
    public void startLoop() {
        if (paused) return; // 用户已暂停：不重新调度，等待手动恢复
        if (host == null) return;
        stop();
        int secs = host.getIntervalSeconds();
        if (secs <= 0) return;
        long intervalMs = secs * 1000L;
        long preMs = Math.max(0, intervalMs - 1000L);
        // 实际触发自动刷新前 1s：提前让"自动刷新中"文字与圆环变绿转动，提示用户即将刷新
        preActivate = () -> {
            if (host != null && host.isViewReady()) setActive(true);
        };
        handler.postDelayed(preActivate, preMs);
        runnable = new Runnable() {
            @Override public void run() {
                if (host == null || !host.isViewReady()) return;
                // 用户正在触摸滑动时跳过本次自动刷新，延迟 2 秒后重试
                if (host.isUserTouching()) {
                    handler.postDelayed(this, 2000);
                    return;
                }
                host.onTick();
            }
        };
        handler.postDelayed(runnable, intervalMs);
    }

    public void stop() {
        if (runnable != null) { handler.removeCallbacks(runnable); runnable = null; }
        if (preActivate != null) { handler.removeCallbacks(preActivate); preActivate = null; }
        setActive(false);
        if (!paused) restoreLabelText();
    }

    public boolean isActive() {
        return active;
    }

    public boolean isPaused() {
        return paused;
    }

    /** 点击"自动刷新中......"：暂停 ↔ 恢复；返回切换后的暂停状态 */
    public boolean togglePause() {
        if (paused) {
            resume();
            return false;
        } else {
            pause();
            return true;
        }
    }

    /** 暂停：取消已调度的下一轮刷新，指示器变暗，文字提示已暂停 */
    public void pause() {
        if (host == null || host.getIntervalSeconds() <= 0) return;
        paused = true;
        if (runnable != null) { handler.removeCallbacks(runnable); runnable = null; }
        if (preActivate != null) { handler.removeCallbacks(preActivate); preActivate = null; }
        setActive(false);
        if (label != null) {
            try { label.setText("自动刷新已暂停"); } catch (Exception ignore) {}
        }
    }

    /** 恢复：还原"自动刷新中"文字并按设置间隔重新调度 */
    public void resume() {
        paused = false;
        restoreLabelText();
        startLoop();
    }

    /** 解除暂停（手动查询时调用）：清暂停状态并还原文字，不立即调度（由查询完成后的续排逻辑决定） */
    public void unpause() {
        paused = false;
        restoreLabelText();
    }

    private void restoreLabelText() {
        if (label != null) {
            try { label.setText("自动刷新中......"); } catch (Exception ignore) {}
        }
    }

    /** 指示器视觉切换：active=true 变绿转动，false 变暗静止；间隔关闭时完全隐藏 */
    public void setActive(boolean a) {
        active = a;
        if (host != null) host.onActiveChanged(a);
        if (indicator == null && label == null) return;
        try {
            boolean enabled = host != null && host.getIntervalSeconds() > 0;
            if (indicator != null) {
                indicator.setVisibility(enabled ? View.VISIBLE : View.GONE);
                indicator.clearAnimation();
                if (a) {
                    indicator.setBackgroundTintList(
                            android.content.res.ColorStateList.valueOf(0xFF00F5D4));
                    indicator.setAlpha(1f);
                    indicator.startAnimation(spinAnim);
                } else {
                    indicator.setBackgroundTintList(
                            android.content.res.ColorStateList.valueOf(0xFF8A9099));
                    indicator.setAlpha(0.35f);
                }
            }
            if (label != null) {
                label.setVisibility(enabled ? View.VISIBLE : View.GONE);
                if (a) {
                    label.setTextColor(context != null
                            ? context.getResources().getColor(R.color.accent, context.getTheme()) : 0xFF00F5D4);
                    label.setAlpha(1f);
                } else {
                    label.setTextColor(context != null
                            ? context.getResources().getColor(R.color.muted, context.getTheme()) : 0xFF8A9099);
                    label.setAlpha(0.5f);
                }
            }
        } catch (Exception ignore) {}
    }

    /** 释放（onDestroyView 用） */
    public void release() {
        stop();
        context = null;
        host = null;
        indicator = null;
        label = null;
        spinAnim = null;
    }
}
