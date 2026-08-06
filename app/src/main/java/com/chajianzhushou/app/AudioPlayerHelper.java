package com.chajianzhushou.app;

import android.content.Context;
import android.media.MediaPlayer;

/**
 * 内置提示音播放（raw 资源）。
 * 统一管理当前播放器：新的播放会先停掉上一个，避免多个提示音叠加/串台。
 */
public class AudioPlayerHelper {

    private static MediaPlayer sCurrentPlayer;

    public static void play(Context ctx, int resId) {
        try {
            stop();
            MediaPlayer mp = MediaPlayer.create(ctx, resId);
            if (mp != null) {
                sCurrentPlayer = mp;
                mp.setOnCompletionListener(p -> {
                    if (sCurrentPlayer == p) sCurrentPlayer = null;
                    try { p.release(); } catch (Exception ignore) {}
                });
                mp.setOnErrorListener((p, what, extra) -> {
                    if (sCurrentPlayer == p) sCurrentPlayer = null;
                    try { p.release(); } catch (Exception ignore) {}
                    return true;
                });
                mp.start();
            }
        } catch (Exception ignore) {}
    }

    /** 停止当前内置提示音（新播放前自动调用，也可主动停止） */
    public static void stop() {
        try {
            if (sCurrentPlayer != null) sCurrentPlayer.stop();
        } catch (Throwable ignore) {}
        try {
            if (sCurrentPlayer != null) sCurrentPlayer.release();
        } catch (Throwable ignore) {}
        sCurrentPlayer = null;
    }

    public static void playSuccess(Context ctx) {
        play(ctx, R.raw.timeout_outbound_success);
    }

    public static void playPartialFail(Context ctx) {
        play(ctx, R.raw.timeout_outbound_partial_fail);
    }

    public static void playServerError(Context ctx) {
        play(ctx, R.raw.server_error);
    }
}
