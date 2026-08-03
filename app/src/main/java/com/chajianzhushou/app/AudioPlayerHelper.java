package com.chajianzhushou.app;

import android.content.Context;
import android.media.MediaPlayer;

public class AudioPlayerHelper {

    public static void play(Context ctx, int resId) {
        try {
            MediaPlayer mp = MediaPlayer.create(ctx, resId);
            if (mp != null) {
                mp.setOnCompletionListener(MediaPlayer::release);
                mp.start();
            }
        } catch (Exception ignore) {}
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
