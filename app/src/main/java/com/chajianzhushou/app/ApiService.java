package com.chajianzhushou.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class ApiService {
    private static final String PREFS_NAME = "chajianzhushou_prefs";
    private static final String KEY_SERVER_IP = "server_ip";
    private static final String DEFAULT_IP = "192.168.1.100";
    private static final int PORT = 3000;

    private final OkHttpClient client;
    private final Context context;
    private final Handler mainHandler;

    public interface ApiCallback {
        void onSuccess(JSONObject response);
        void onError(String error);
    }

    public interface ApiArrayCallback {
        void onSuccess(JSONArray response);
        void onError(String error);
    }

    public ApiService(Context context) {
        this.context = context;
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build();
    }

    public OkHttpClient getOkHttpClient() {
        return client;
    }

    public Context getContext() {
        return context;
    }

    public String getBaseUrl() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String ip = prefs.getString(KEY_SERVER_IP, DEFAULT_IP);
        return "http://" + ip + ":" + PORT;
    }

    /**
     * 将服务器返回的图片字段转成可访问的绝对 URL：
     *   - 以 http:// / https:// / data: / file: 开头的原样返回
     *   - 以 / 开头的相对路径拼接 baseUrl
     *   - 其他相对路径也拼接 baseUrl + /
     */
    public String resolveImageUrl(String rawUrl) {
        if (rawUrl == null) return "";
        String s = rawUrl.trim();
        if (s.length() == 0) return "";
        String low = s.toLowerCase();
        if (low.startsWith("http://") || low.startsWith("https://")
                || low.startsWith("data:") || low.startsWith("file:") || low.startsWith("content:")) {
            return s;
        }
        String base = getBaseUrl();
        if (s.startsWith("/")) return base + s;
        return base + "/" + s;
    }

    public void saveServerIp(String ip) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_SERVER_IP, ip).apply();
    }

    public String getSavedServerIp() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_SERVER_IP, DEFAULT_IP);
    }

    public void queryPackageRaw(JSONObject body, ApiCallback callback) {
        Request request = new Request.Builder()
                .url(getBaseUrl() + "/api/query")
                .post(RequestBody.create(body.toString(), MediaType.parse("application/json")))
                .build();
        enqueue(request, callback);
    }

    public void refreshToken(ApiCallback callback) {
        Request request = new Request.Builder()
                .url(getBaseUrl() + "/api/login")
                .post(RequestBody.create("", MediaType.parse("application/json")))
                .build();
        enqueue(request, callback);
    }

    public void getSettings(ApiCallback callback) {
        Request request = new Request.Builder()
                .url(getBaseUrl() + "/api/settings")
                .get()
                .build();
        enqueue(request, callback);
    }

    public void getTimeoutPackages(ApiArrayCallback callback) {
        Request request = new Request.Builder()
                .url(getBaseUrl() + "/api/timeout/query")
                .get()
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                mainHandler.post(() -> callback.onError("网络错误: " + e.getMessage()));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String responseBody = response.body() != null ? response.body().string() : "[]";
                try {
                    JSONArray json = new JSONArray(responseBody);
                    mainHandler.post(() -> callback.onSuccess(json));
                } catch (JSONException e) {
                    try {
                        JSONObject obj = new JSONObject(responseBody);
                        if (obj.has("data")) {
                            JSONArray arr = obj.getJSONArray("data");
                            mainHandler.post(() -> callback.onSuccess(arr));
                        } else {
                            mainHandler.post(() -> callback.onSuccess(new JSONArray()));
                        }
                    } catch (JSONException e2) {
                        mainHandler.post(() -> callback.onError("解析错误: " + e2.getMessage()));
                    }
                }
            }
        });
    }

    /** 通用 JSON 请求执行器：统一网络错误/解析错误处理，并在主线程回调 */
    private void enqueue(Request request, ApiCallback callback) {
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                mainHandler.post(() -> callback.onError("网络错误: " + e.getMessage()));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String responseBody = response.body() != null ? response.body().string() : "{}";
                try {
                    JSONObject json = new JSONObject(responseBody);
                    mainHandler.post(() -> callback.onSuccess(json));
                } catch (JSONException e) {
                    mainHandler.post(() -> callback.onError("解析错误: " + e.getMessage()));
                }
            }
        });
    }
}
