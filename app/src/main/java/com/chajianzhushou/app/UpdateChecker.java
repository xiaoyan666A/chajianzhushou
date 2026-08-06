package com.chajianzhushou.app;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * GitHub Releases 检测更新：
 * - 拉取公开仓库 latest release（tag_name / body / APK asset）；
 * - 与本地 versionName 对比，有新版弹更新对话框；
 * - 下载 APK 到缓存目录，FileProvider 拉起系统安装。
 * 发布前把 GITHUB_OWNER / GITHUB_REPO 改成你的仓库。
 */
public final class UpdateChecker {

    // 发布仓库（GitHub Releases 用于检测更新与 APK 分发）
    private static final String GITHUB_OWNER = "xiaoyan666A";
    private static final String GITHUB_REPO = "chajianzhushou";
    private static final String API_URL = "https://api.github.com/repos/%s/%s/releases/latest";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();

    private UpdateChecker() {}

    /**
     * 检查更新。
     * @param manual true=手动检查（失败/无更新都会提示）；false=启动静默检查（只有发现新版才提示）
     */
    public static void check(final Context ctx, final boolean manual) {
        final Context app = ctx.getApplicationContext();
        Threads.io().execute(() -> {
            try {
                String url = String.format(API_URL, GITHUB_OWNER, GITHUB_REPO);
                Request req = new Request.Builder().url(url).header("User-Agent", "chajianzhushou").build();
                Response resp = HTTP.newCall(req).execute();
                if (resp == null || !resp.isSuccessful()) {
                    if (manual) toast(app, "检查更新失败（网络异常或仓库未发布版本）");
                    return;
                }
                String bodyStr = resp.body() != null ? resp.body().string() : "";
                if (bodyStr.isEmpty()) {
                    if (manual) toast(app, "检查更新失败：响应为空");
                    return;
                }
                JSONObject json = new JSONObject(bodyStr);
                String tag = json.optString("tag_name", "");
                String notes = json.optString("body", "");
                String apkUrl = "";
                JSONArray assets = json.optJSONArray("assets");
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject a = assets.optJSONObject(i);
                        String name = (a == null) ? "" : a.optString("name", "");
                        if (name.endsWith(".apk")) {
                            apkUrl = a.optString("browser_download_url", "");
                            break;
                        }
                    }
                }
                String current = getVersionName(app);
                int cmp = compareVersions(stripV(tag), stripV(current));
                if (cmp <= 0) {
                    if (manual) toast(app, "已是最新版本（" + current + "）");
                    return;
                }
                if (apkUrl.isEmpty()) {
                    if (manual) toast(app, "新版本 " + tag + " 未附带 APK 文件");
                    return;
                }
                final String fApkUrl = apkUrl;
                final String fTag = tag;
                MAIN.post(() -> showUpdateDialog(app, fTag, notes, fApkUrl));
            } catch (Exception e) {
                if (manual) toast(app, "检查更新失败: " + e.getMessage());
            }
        });
    }

    private static void showUpdateDialog(final Context ctx, final String version, final String notes, final String apkUrl) {
        try {
            new AlertDialog.Builder(ctx)
                    .setTitle("发现新版本 " + version)
                    .setMessage((notes == null || notes.trim().isEmpty()) ? "是否立即下载更新？" : notes.trim())
                    .setPositiveButton("立即更新", (d, w) -> downloadAndInstall(ctx, apkUrl))
                    .setNegativeButton("以后再说", null)
                    .show();
        } catch (Exception ignore) {}
    }

    private static void downloadAndInstall(final Context ctx, final String apkUrl) {
        final ProgressDialog pd = new ProgressDialog(ctx);
        pd.setTitle("正在下载更新");
        pd.setMessage("请稍候...");
        pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        pd.setCancelable(false);
        pd.show();
        Threads.io().execute(() -> {
            try {
                Request req = new Request.Builder().url(apkUrl).build();
                Response resp = HTTP.newCall(req).execute();
                if (resp == null || !resp.isSuccessful()) {
                    MAIN.post(() -> {
                        try { pd.dismiss(); } catch (Exception ignore) {}
                        toast(ctx, "下载失败");
                    });
                    return;
                }
                long total = resp.body() != null ? resp.body().contentLength() : -1;
                File dir = new File(ctx.getCacheDir(), "updates");
                if (!dir.exists()) dir.mkdirs();
                File apk = new File(dir, "app-update.apk");
                long done = 0;
                try (InputStream in = resp.body().byteStream();
                     FileOutputStream fos = new FileOutputStream(apk)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        fos.write(buf, 0, n);
                        done += n;
                        if (total > 0) {
                            final int pct = (int) (done * 100 / total);
                            MAIN.post(() -> {
                                try { pd.setProgress(pct); } catch (Exception ignore) {}
                            });
                        }
                    }
                    fos.flush();
                }
                MAIN.post(() -> {
                    try { pd.dismiss(); } catch (Exception ignore) {}
                    installApk(ctx, apk);
                });
            } catch (Exception e) {
                MAIN.post(() -> {
                    try { pd.dismiss(); } catch (Exception ignore) {}
                    toast(ctx, "下载失败: " + e.getMessage());
                });
            }
        });
    }

    private static void installApk(Context ctx, File apk) {
        try {
            Uri uri = FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".fileprovider", apk);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);
        } catch (Exception e) {
            toast(ctx, "安装失败: " + e.getMessage());
        }
    }

    private static String getVersionName(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return pi.versionName;
        } catch (Exception e) {
            return "";
        }
    }

    private static String stripV(String v) {
        if (v == null) return "";
        String s = v.trim();
        if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1);
        return s;
    }

    /** 简单版本比较：按 . 分段数字比较；非数字段忽略 */
    private static int compareVersions(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int x = (i < pa.length) ? parseInt(pa[i]) : 0;
            int y = (i < pb.length) ? parseInt(pb[i]) : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }

    private static int parseInt(String s) {
        try {
            String d = s.replaceAll("[^0-9]", "");
            return d.isEmpty() ? 0 : Integer.parseInt(d);
        } catch (Exception e) {
            return 0;
        }
    }

    private static void toast(final Context ctx, final String msg) {
        MAIN.post(() -> {
            try { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show(); } catch (Exception ignore) {}
        });
    }
}
