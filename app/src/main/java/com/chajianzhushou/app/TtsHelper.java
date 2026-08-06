package com.chajianzhushou.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * TTS 播放辅助类 —— 优先使用小米 MiMo-V2.5-TTS HTTP 接口，
 * 当用户未配置 MiMo API Key、或调用失败时，自动回退到安卓系统 TextToSpeech。
 *
 * MiMo 配置全部从 SharedPreferences("chajianzhushou_prefs") 中读取：
 *   - "mimo_api_key"            : MiMo API Key（如果空则回退系统TTS）
 *   - "tts_enabled"             : 总开关（如果 false 则 speak 静默）
 *   - KEY_TTS_VOICE             : 0-3 对应 [冰糖, 茉莉, 苏打, 白桦]
 *   - KEY_TTS_STYLE             : 0-9  对应 arrays.xml tts_style_options
 *   - KEY_TTS_CUSTOM_STYLE(String) : 用户自定义 style 文本，当 style spinner 对应项启用时追加
 *   - KEY_TTS_SPEED             : 5-15 (0.5x~1.5x)
 */
public class TtsHelper {
    private static final String TAG = "TtsHelper";
    private static final String PREFS_NAME = "chajianzhushou_prefs";
    private static final String KEY_MIMO_API = "mimo_api_key";
    private static final String KEY_TTS_ENABLED = "tts_enabled";
    private static final String KEY_TTS_VOICE = "tts_voice";
    private static final String KEY_TTS_STYLE = "tts_style";
    private static final String KEY_TTS_CUSTOM_STYLE = "tts_custom_style";
    private static final String KEY_TTS_SPEED = "tts_speed";

    private static final String MIMO_URL = "https://api.xiaomimimo.com/v1/chat/completions";
    private static final String MIMO_MODEL = "mimo-v2.5-tts";

    // 与 arrays.xml tts_voice_options 顺序一致（0-3）
    private static final String[] MIMO_VOICE_IDS = {"冰糖", "茉莉", "苏打", "白桦"};

    // 与 arrays.xml tts_style_options 顺序一致（0-9）：每个风格对应一段具体稳定的自然语言指令，
    // 比只传"干练"两个字更能约束语气与清晰度（MiMo 为 LLM 合成，指令越具体语气越稳定）
    private static final String[] STYLE_PROMPTS = {
            "",
            "用温柔、轻柔的语气朗读，语速适中偏慢，语气柔和温暖，吐字清晰",
            "用活泼、轻快的语气朗读，节奏明快，声音明亮，吐字清晰",
            "用严肃、端正的语气朗读，语速适中，语气庄重平稳，吐字清晰",
            "用干练、利落的语气朗读，语速稍快，节奏明快，咬字清晰，语气稳定干脆",
            "用甜美、明亮的语气朗读，语速适中，声音圆润，吐字清晰",
            "用慵懒、松弛的语气朗读，语速偏慢，语气舒缓，吐字清晰",
            "用俏皮、灵动的语气朗读，轻快活泼，吐字清晰",
            "用低沉、有磁性的语气朗读，语速适中偏慢，沉稳有质感，吐字清晰",
            "用清亮、通透的语气朗读，声音明亮，吐字清晰"
    };

    private static TtsHelper instance;

    // 系统 TTS（回退）
    private TextToSpeech systemTts;
    private boolean systemInitialized = false;
    // 当前系统TTS播报的 utteranceId，用于回调令牌校验（旧播报被打断后的完成事件不得触发当前回调）
    private volatile String systemUtteranceId;
    // 播报代次：每次 speak/stop 递增，MiMo 异步响应回来后先校验，过期响应直接丢弃（防止旧音频打断/覆盖新播报）
    private volatile int speechGeneration = 0;

    // MiMo 播放
    private MediaPlayer currentPlayer;
    private File lastAudioFile;

    private TtsCallback currentCallback;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build();

    public interface TtsCallback {
        void onDone();
        void onError(String error);
    }

    public static synchronized TtsHelper getInstance() {
        if (instance == null) instance = new TtsHelper();
        return instance;
    }

    // ====== 初始化（系统TTS预初始化，需要 Context）======
    public void init(Context ctx) {
        // MiMo 不需要 init，系统 TTS 才需要——这里只懒初始化
        if (systemInitialized || ctx == null) return;
        try {
            Context appCtx = ctx.getApplicationContext();
            systemTts = new TextToSpeech(appCtx, status -> {
                if (status == TextToSpeech.SUCCESS) {
                    try {
                        systemTts.setLanguage(Locale.CHINESE);
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            systemTts.setAudioAttributes(new AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_MEDIA)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                    .build());
                        }
                        systemInitialized = true;
                    } catch (Throwable ignore) {}
                }
            });
            systemTts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String utteranceId) {}
                @Override public void onDone(String utteranceId) {
                    // 令牌校验：只处理当前这轮播报的完成事件，避免旧语音被 stop 后的回调串扰
                    if (!utteranceId.equals(systemUtteranceId)) return;
                    systemUtteranceId = null;
                    final TtsCallback cb = currentCallback;
                    currentCallback = null;
                    if (cb != null) mainHandler.post(cb::onDone);
                }
                // 基类抽象方法（已弃用，但必须覆盖；标注 @Deprecated 抑制编译警告）
                @Override @Deprecated
                public void onError(String utteranceId) {
                    if (!utteranceId.equals(systemUtteranceId)) return;
                    systemUtteranceId = null;
                    final TtsCallback cb = currentCallback;
                    currentCallback = null;
                    if (cb != null) mainHandler.post(() -> cb.onError("系统TTS错误"));
                }
                @Override @Deprecated
                public void onError(String utteranceId, int errorCode) {
                    if (!utteranceId.equals(systemUtteranceId)) return;
                    systemUtteranceId = null;
                    final TtsCallback cb = currentCallback;
                    currentCallback = null;
                    if (cb != null) mainHandler.post(() -> cb.onError("系统TTS错误:" + errorCode));
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "init system TTS failed: " + t.getMessage());
        }
    }

    // ====== 主入口：优先 MiMo HTTP，失败回退系统 TTS ======
    public void speak(Context ctx, String text, TtsCallback callback) {
        stop();
        final int gen = ++speechGeneration;
        currentCallback = callback;

        if (ctx == null || text == null || text.isEmpty()) {
            notifyError("无效的播报参数");
            return;
        }

        SharedPreferences prefs = ctx.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        // TTS 总开关
        boolean ttsEnabled = prefs.getBoolean(KEY_TTS_ENABLED, false);
        if (!ttsEnabled) {
            notifyDone();
            return;
        }

        String apiKey = prefs.getString(KEY_MIMO_API, "");
        if (apiKey == null || apiKey.trim().isEmpty()) {
            // 没配置 MiMo Key，直接系统TTS
            Log.d(TAG, "MiMo Key 为空，使用系统TTS");
            speakWithSystem(ctx, text);
            return;
        }

        // 构造 MiMo TTS 请求体
        try {
            int voiceIdx = prefs.getInt(KEY_TTS_VOICE, 0);
            int styleIdx = prefs.getInt(KEY_TTS_STYLE, 0);
            String customStyle = prefs.getString(KEY_TTS_CUSTOM_STYLE, "");
            int speedVal = prefs.getInt(KEY_TTS_SPEED, 10);

            String voice = voiceIdx >= 0 && voiceIdx < MIMO_VOICE_IDS.length
                    ? MIMO_VOICE_IDS[voiceIdx] : MIMO_VOICE_IDS[0];

            // 风格指令（user message content）
            StringBuilder styleInst = new StringBuilder();
            String[] styleNames = ctx.getResources().getStringArray(R.array.tts_style_options);
            if (styleIdx >= 0 && styleIdx < styleNames.length && styleIdx != 0) {
                styleInst.append(STYLE_PROMPTS[styleIdx]).append("。");
            }
            if (customStyle != null && customStyle.trim().length() > 0) {
                styleInst.append(customStyle.trim()).append("。");
            }
            float speed = (speedVal >= 5 && speedVal <= 15) ? (speedVal / 10.0f) : 1.0f;
            if (Math.abs(speed - 1.0f) > 0.001f) {
                styleInst.append("语速：").append(String.format("%.1f", speed)).append("倍。");
            }

            // 如果 styleInst 非空，把内容前置 (style) 标签 或 user message 双保险（双保险都加）
            String userContent = styleInst.length() > 0 ? styleInst.toString() : "用自然、清晰、平稳的中文普通话朗读，吐字清楚，语速适中，语气稳定一致";
            // assistant 内容：如果有 style 也把 (风格) 前缀加在文本最前面，提升生效概率
            String assistantContent;
            if (styleIdx != 0 || (customStyle != null && customStyle.trim().length() > 0)) {
                String tag = styleIdx >= 0 && styleIdx < styleNames.length ? styleNames[styleIdx] : "";
                StringBuilder allStyles = new StringBuilder();
                if (tag != null && tag.length() > 0 && !"默认".equals(tag)) {
                    allStyles.append(tag);
                }
                if (customStyle != null && customStyle.trim().length() > 0) {
                    if (allStyles.length() > 0) allStyles.append(" ");
                    allStyles.append(customStyle.trim());
                }
                if (allStyles.length() > 0) {
                    assistantContent = "(" + allStyles + ")" + text;
                } else {
                    assistantContent = text;
                }
            } else {
                assistantContent = text;
            }

            JSONObject body = new JSONObject();
            body.put("model", MIMO_MODEL);
            JSONArray msgs = new JSONArray();
            msgs.put(new JSONObject().put("role", "user").put("content", userContent));
            msgs.put(new JSONObject().put("role", "assistant").put("content", assistantContent));
            body.put("messages", msgs);
            JSONObject audio = new JSONObject();
            audio.put("format", "wav");
            audio.put("voice", voice);
            body.put("audio", audio);

            final String postBody = body.toString();
            Log.d(TAG, "MiMo TTS 请求 voice=" + voice + " text=" + text);
            // 异步请求
            Threads.io().execute(() -> mimoSpeakAsync(ctx, apiKey, postBody, text, gen));
        } catch (Throwable t) {
            Log.w(TAG, "构造MiMo请求失败，回退系统TTS: " + t.getMessage());
            speakWithSystem(ctx, text);
        }
    }

    // ====== MiMo HTTP 实现 ======
    private void mimoSpeakAsync(Context ctx, String apiKey, String postBody, String fallbackText, int gen) {
        try {
            Request r = new Request.Builder()
                    .url(MIMO_URL)
                    .header("api-key", apiKey)
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(postBody, MediaType.parse("application/json")))
                    .build();
            Response resp = httpClient.newCall(r).execute();
            if (resp == null || !resp.isSuccessful()) {
                String code = (resp == null ? "null" : String.valueOf(resp.code()));
                Log.w(TAG, "MiMo TTS HTTP错误 code=" + code);
            mainHandler.post(() -> { if (gen != speechGeneration) return; speakWithSystem(ctx, fallbackText); });
                return;
            }
            ResponseBody rb = resp.body();
            if (rb == null) {
                Log.w(TAG, "MiMo TTS 空响应体");
                mainHandler.post(() -> { if (gen != speechGeneration) return; speakWithSystem(ctx, fallbackText); });
                return;
            }
            String json = rb.string();
            JSONObject j = new JSONObject(json);
            String b64 = j.optJSONArray("choices")
                    .optJSONObject(0)
                    .optJSONObject("message")
                    .optJSONObject("audio")
                    .optString("data", "");
            if (b64 == null || b64.isEmpty()) {
                // 错误信息
                String errMsg = j.optString("message", "未知错误");
                Log.w(TAG, "MiMo TTS 无音频: " + errMsg);
                mainHandler.post(() -> { if (gen != speechGeneration) return; speakWithSystem(ctx, fallbackText); });
                return;
            }
            byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
            // 写临时文件，用 MediaPlayer 播放
            File outDir = new File(ctx.getCacheDir(), "tts");
            if (!outDir.exists()) outDir.mkdirs();
            File out = File.createTempFile("mimo_", ".wav", outDir);
            try (FileOutputStream fos = new FileOutputStream(out)) {
                fos.write(bytes);
                fos.flush();
            }
            lastAudioFile = out;
            Log.d(TAG, "MiMo TTS 收到音频 bytes=" + bytes.length + " file=" + out.getName());

            // 主线程播放
            mainHandler.post(() -> { if (gen != speechGeneration) return; playWav(out); });
        } catch (Throwable t) {
            Log.w(TAG, "MiMo TTS异常: " + t.getMessage());
                mainHandler.post(() -> { if (gen != speechGeneration) return; speakWithSystem(ctx, fallbackText); });
        }
    }

    private void playWav(File wav) {
        try {
            stopPlayer();
            MediaPlayer mp = new MediaPlayer();
            currentPlayer = mp;
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build());
            mp.setDataSource(wav.getAbsolutePath());
            mp.setOnCompletionListener(player -> {
                releasePlayer();
                notifyDone();
            });
            mp.setOnErrorListener((player, what, extra) -> {
                Log.w(TAG, "MediaPlayer 播放错误 what=" + what + " extra=" + extra);
                releasePlayer();
                notifyError("播放错误");
                return true;
            });
            mp.setOnPreparedListener(mp2 -> {
                if (currentPlayer == mp2) {
                    try { mp2.start(); } catch (Throwable ignore) {}
                }
            });
            mp.prepareAsync();
        } catch (IOException e) {
            Log.w(TAG, "playWav 失败: " + e.getMessage());
            releasePlayer();
            notifyError("音频播放失败");
        }
    }

    private void stopPlayer() {
        try {
            if (currentPlayer != null && currentPlayer.isPlaying()) {
                currentPlayer.stop();
            }
        } catch (Throwable ignore) {}
        releasePlayer();
    }

    private void releasePlayer() {
        if (currentPlayer != null) {
            try { currentPlayer.release(); } catch (Throwable ignore) {}
            currentPlayer = null;
        }
    }

    // ====== 系统 TTS 回退 ======
    private void speakWithSystem(Context ctx, String text) {
        Log.d(TAG, "使用系统TTS播报: " + text);
        init(ctx);
        if (systemTts == null) {
            notifyError("系统TTS未就绪");
            return;
        }
        try {
            final String uid = "sys_tts_" + System.currentTimeMillis();
            systemUtteranceId = uid;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                android.os.Bundle params = new android.os.Bundle();
                params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f);
                systemTts.speak(text, TextToSpeech.QUEUE_FLUSH, params, uid);
            } else {
                Bundle params = new Bundle();
                params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f);
                systemTts.speak(text, TextToSpeech.QUEUE_FLUSH, params, uid);
            }
        } catch (Throwable t) {
            systemUtteranceId = null;
            notifyError("系统TTS播报失败: " + t.getMessage());
        }
    }

    // ====== 状态控制 ======
    public boolean isSpeaking() {
        try {
            if (currentPlayer != null && currentPlayer.isPlaying()) return true;
        } catch (Throwable ignore) {}
        try {
            if (systemTts != null && systemTts.isSpeaking()) return true;
        } catch (Throwable ignore) {}
        return false;
    }

    public void stop() {
        speechGeneration++;
        stopPlayer();
        try {
            if (systemTts != null) systemTts.stop();
        } catch (Throwable ignore) {}
        // 使令牌失效：被 stop 的播报后续到达的 onDone/onError 一律忽略
        systemUtteranceId = null;
        currentCallback = null;
    }

    public void shutdown() {
        stop();
        try {
            if (systemTts != null) {
                systemTts.shutdown();
                systemTts = null;
                systemInitialized = false;
            }
        } catch (Throwable ignore) {}
        // 清理临时文件
        if (lastAudioFile != null) {
            try { lastAudioFile.delete(); } catch (Throwable ignore) {}
            lastAudioFile = null;
        }
        instance = null;
    }

    // ====== 通知回调（切换到主线程） ======
    private void notifyDone() {
        final TtsCallback cb = currentCallback;
        currentCallback = null;
        if (cb != null) mainHandler.post(cb::onDone);
    }

    private void notifyError(String err) {
        final TtsCallback cb = currentCallback;
        currentCallback = null;
        if (cb != null) mainHandler.post(() -> cb.onError(err));
    }
}
