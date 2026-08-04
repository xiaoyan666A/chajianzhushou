package com.chajianzhushou.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class SyncClient {
    private static final String TAG = "SyncClient";
    private static final int RECONNECT_DELAY_MS = 5000;

    public interface SyncCallback {
        void onQueryInputReceived(String value);
        void onQueryTriggerReceived(String billCode, String type);
        void onGridViewChanged(boolean gridView);
        void onShowDeliveredChanged(boolean showDelivered);
        void onSettingsChanged(JSONObject settings);
        void onConnected();
        void onDisconnected();
        void onError(String error);
    }

    private final ApiService apiService;
    private final Handler mainHandler;
    private final OkHttpClient sseClient;
    private SyncCallback callback;
    private Thread sseThread;
    private volatile boolean shouldRun;
    private volatile int generation; // 连接代数：disconnect/connect 交替时让旧循环线程失效，防止双连接
    private volatile Call activeCall; // 当前 SSE 连接，disconnect 时 cancel 以中断阻塞的 readLine
    private volatile long lastInputSyncAt;
    private String lastInputValue = "";

    public SyncClient(ApiService apiService) {
        this.apiService = apiService;
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.sseClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MINUTES)
                .pingInterval(25, TimeUnit.SECONDS)
                .build();
    }

    public void setCallback(SyncCallback callback) {
        this.callback = callback;
    }

    public void connect() {
        disconnect();
        final int gen = ++generation;
        shouldRun = true;
        sseThread = new Thread(() -> sseLoop(gen), "SSE-Loop");
        sseThread.setDaemon(true);
        sseThread.start();
    }

    public void disconnect() {
        generation++; // 使旧循环线程失效：即使它还在 sleep/重连中，醒来后也会直接退出
        shouldRun = false;
        // 主动 cancel 正在阻塞的 SSE 连接：仅 interrupt 无法中断 readLine，会导致线程泄漏
        Call call = activeCall;
        if (call != null) {
            try { call.cancel(); } catch (Exception ignore) {}
        }
        if (sseThread != null) {
            try { sseThread.interrupt(); } catch (Exception ignore) {}
            sseThread = null;
        }
    }

    private void sseLoop(final int gen) {
        while (shouldRun && gen == generation) {
            Response response = null;
            InputStream in = null;
            BufferedReader reader = null;
            try {
                String url = apiService.getBaseUrl() + "/api/events?clientType=mobile&_t=" + System.currentTimeMillis();
                Log.d(TAG, "SSE connecting: " + url);
                try {
                    Context ctx = apiService.getContext();
                    if (ctx != null) LogRecorder.info(ctx, "Sync", "SSE连接", url);
                } catch (Exception ignore) {}
                Request request = new Request.Builder().url(url).build();
                Call call = sseClient.newCall(request);
                activeCall = call;
                response = call.execute();
                if (response == null || !response.isSuccessful()) {
                    Log.w(TAG, "SSE response fail: " + (response == null ? "null" : response.code()));
                    try {
                        Context ctx = apiService.getContext();
                        if (ctx != null) LogRecorder.warn(ctx, "Sync", "SSE响应失败", response == null ? "null" : ("HTTP " + response.code()));
                    } catch (Exception ignore) {}
                    if (callback != null) mainHandler.post(() -> callback.onDisconnected());
                    safeSleep(RECONNECT_DELAY_MS);
                    continue;
                }
                if (callback != null) mainHandler.post(() -> callback.onConnected());
                in = response.body().byteStream();
                reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
                String line;
                StringBuilder eventBuffer = new StringBuilder();
                String currentEventName = "message";
                while (shouldRun && (line = reader.readLine()) != null) {
                    if (line.startsWith("event:")) {
                        currentEventName = line.substring(6).trim();
                    } else if (line.startsWith("data:")) {
                        eventBuffer.append(line.substring(5).trim());
                    } else if (line.length() == 0) {
                        if (eventBuffer.length() > 0) {
                            String data = eventBuffer.toString();
                            dispatchEvent(currentEventName, data);
                        }
                        eventBuffer.setLength(0);
                        currentEventName = "message";
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "SSE error: " + e.getMessage());
                try {
                    Context ctx = apiService.getContext();
                    if (ctx != null) LogRecorder.error(ctx, "Sync", "SSE错误", e.getMessage());
                } catch (Exception ignore) {}
                if (callback != null) mainHandler.post(() -> callback.onError(e.getMessage()));
            } finally {
                safeClose(reader);
                safeClose(in);
                try { if (response != null) response.close(); } catch (Exception ignore) {}
                activeCall = null;
            }
            // 若期间已 disconnect/重连，旧线程不再补发 onDisconnected 也不再重连
            if (gen != generation) return;
            if (callback != null) mainHandler.post(() -> callback.onDisconnected());
            safeSleep(RECONNECT_DELAY_MS);
        }
    }

    private void dispatchEvent(String eventName, String data) {
        try {
            if (callback == null) return;
            JSONObject obj = new JSONObject(data);
            String source = obj.optString("source", "");
            // 手机端只处理电脑端(web)发来的事件；忽略自己(mobile)发的，防止回环无限触发
            if ("mobile".equals(source)) return;

            if ("query-input".equals(eventName)) {
                String v = obj.optString("value", "");
                mainHandler.post(() -> callback.onQueryInputReceived(v));
            }
            if ("query-trigger".equals(eventName)) {
                String bc = obj.optString("billCode", "");
                String ty = obj.has("queryType") ? obj.optString("queryType", "") : obj.optString("type", "");
                mainHandler.post(() -> callback.onQueryTriggerReceived(bc, ty));
            }
            if ("grid-view".equals(eventName)) {
                boolean gv = obj.optBoolean("gridView", false);
                mainHandler.post(() -> callback.onGridViewChanged(gv));
            }
            if ("show-delivered".equals(eventName)) {
                boolean sd = obj.optBoolean("showDelivered", true);
                mainHandler.post(() -> callback.onShowDeliveredChanged(sd));
            }
            if ("settings".equals(eventName)) {
                JSONObject settingsData = null;
                if (obj.has("data") && !obj.isNull("data")) {
                    Object d = obj.get("data");
                    if (d instanceof JSONObject) settingsData = (JSONObject) d;
                }
                if (settingsData == null) settingsData = obj;
                final JSONObject finalSettings = settingsData;
                mainHandler.post(() -> callback.onSettingsChanged(finalSettings));
            }
        } catch (Exception e) {
            Log.w(TAG, "dispatchEvent parse fail: " + e.getMessage());
            try {
                Context ctx = apiService.getContext();
                if (ctx != null) LogRecorder.warn(ctx, "Sync", "SSE事件解析失败", e.getMessage());
            } catch (Exception ignore) {}
        }
    }

    private static void safeSleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
    private static void safeClose(java.io.Closeable c) { try { if (c != null) c.close(); } catch (Exception ignore) {} }

    // ====== 上行同步：手机 → 电脑 只保留输入框和查询触发 ======
    public void sendInputSync(final String value) {
        lastInputValue = value;
        final long t0 = System.currentTimeMillis();
        lastInputSyncAt = t0;
        mainHandler.postDelayed(() -> {
            if (t0 != lastInputSyncAt) return;
            final String v = lastInputValue;
            Threads.io().execute(() -> {
                try {
                    JSONObject body = new JSONObject();
                    body.put("value", v);
                    Request r = new Request.Builder()
                            .url(apiService.getBaseUrl() + "/api/sync/query-input")
                            .post(RequestBody.create(body.toString(), MediaType.parse("application/json")))
                            .build();
                    Response resp = sseClient.newCall(r).execute();
                    if (resp != null) resp.close();
                } catch (Exception e) {
                    Log.w(TAG, "sendInputSync fail: " + e.getMessage());
                    try {
                        Context ctx = apiService.getContext();
                        if (ctx != null) LogRecorder.warn(ctx, "Sync", "sendInputSync失败", e.getMessage());
                    } catch (Exception ignore) {}
                }
            });
        }, 300);
    }

    public void sendQueryTrigger(final String billCode, final String type, final boolean showDelivered) {
        Threads.io().execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("billCode", billCode == null ? "" : billCode);
                body.put("type", type == null ? "" : type);
                body.put("showDelivered", showDelivered);
                Request r = new Request.Builder()
                        .url(apiService.getBaseUrl() + "/api/sync/query-trigger")
                        .post(RequestBody.create(body.toString(), MediaType.parse("application/json")))
                        .build();
                Response resp = sseClient.newCall(r).execute();
                if (resp != null) resp.close();
            } catch (Exception e) {
                Log.w(TAG, "sendQueryTrigger fail: " + e.getMessage());
                try {
                    Context ctx = apiService.getContext();
                    if (ctx != null) LogRecorder.warn(ctx, "Sync", "sendQueryTrigger失败", e.getMessage());
                } catch (Exception ignore) {}
            }
        });
    }

    public void sendGridViewSync(final boolean gridView) {
        Threads.io().execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("gridView", gridView);
                Request r = new Request.Builder()
                        .url(apiService.getBaseUrl() + "/api/sync/grid-view")
                        .post(RequestBody.create(body.toString(), MediaType.parse("application/json")))
                        .build();
                Response resp = sseClient.newCall(r).execute();
                if (resp != null) resp.close();
            } catch (Exception e) {
                Log.w(TAG, "sendGridViewSync fail: " + e.getMessage());
                try {
                    Context ctx = apiService.getContext();
                    if (ctx != null) LogRecorder.warn(ctx, "Sync", "sendGridViewSync失败", e.getMessage());
                } catch (Exception ignore) {}
            }
        });
    }

    public void sendShowDeliveredSync(final boolean showDelivered) {
        Threads.io().execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("showDelivered", showDelivered);
                Request r = new Request.Builder()
                        .url(apiService.getBaseUrl() + "/api/sync/show-delivered")
                        .post(RequestBody.create(body.toString(), MediaType.parse("application/json")))
                        .build();
                Response resp = sseClient.newCall(r).execute();
                if (resp != null) resp.close();
            } catch (Exception e) {
                Log.w(TAG, "sendShowDeliveredSync fail: " + e.getMessage());
                try {
                    Context ctx = apiService.getContext();
                    if (ctx != null) LogRecorder.warn(ctx, "Sync", "sendShowDeliveredSync失败", e.getMessage());
                } catch (Exception ignore) {}
            }
        });
    }
}
