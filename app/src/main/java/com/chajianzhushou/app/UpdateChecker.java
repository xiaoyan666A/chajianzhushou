package com.chajianzhushou.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
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
 * - 与本机 versionName 对比，有新版弹更新对话框；
 * - Release 说明（body）中包含“强制更新”字样时，弹窗不可取消、只能立即更新；
 * - 下载 APK 支持断点续传（缓存 app-update.apk.part，带 Range 请求，失败后重试不重复下载）；
 * - 安装前校验下载 APK 签名与本机已安装包一致，防止被替换成恶意包。
 * 发布前把 GITHUB_OWNER / GITHUB_REPO 改成你的仓库。
 */
public final class UpdateChecker {

    // 发布仓库（GitHub Releases 用于检测更新与 APK 分发）
    private static final String GITHUB_OWNER = "xiaoyan666A";
    private static final String GITHUB_REPO = "chajianzhushou";
    private static final String GITHUB_API = "https://api.github.com/repos/%s/%s/releases/latest";
    // Release 说明中包含该字样时视为强制更新
    private static final String FORCE_MARK = "强制更新";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();

    private UpdateChecker() {}

    /**
     * 检查更新。
     * @param manual true=手动检查（失败/无更新都会提示）；false=启动静默检查（只有发现新版才提示）
     */
    public static void check(final Context ctx, final boolean manual) {
        check(ctx, manual, null);
    }

    /** 检查结果回调：reachedServer=true 表示成功连上服务器并拿到响应 */
    public interface CheckCallback {
        void onDone(boolean reachedServer);
    }

    /**
     * 检查更新（带结果回调，用于静默检查的节流控制）。
     * 只有成功拿到服务器响应（哪怕结果是"无新版"）才算检查成功；
     * 网络失败不回调成功，避免把"失败的检查"记进 24 小时节流。
     */
    public static void check(final Context ctx, final boolean manual, final CheckCallback cb) {
        final Context app = ctx.getApplicationContext();
        // 弹窗必须用 Activity 上下文（Application 上下文没有窗口令牌，show() 会抛 BadTokenException 被静默吞掉）
        final Context uiCtx = ctx;
        if (manual) toast(app, "正在检查更新...");
        Threads.io().execute(() -> {
            try {
                String url = String.format(GITHUB_API, GITHUB_OWNER, GITHUB_REPO);
                Request req = new Request.Builder().url(url).header("User-Agent", "chajianzhushou").build();
                Response resp = HTTP.newCall(req).execute();
                if (resp == null || !resp.isSuccessful()) {
                    String msg = "检查更新失败（网络异常或仓库未发布版本）";
                    if (manual) toast(app, msg);
                    log(app, "检查更新失败", msg + "（HTTP " + (resp == null ? "null" : resp.code()) + "）");
                    if (cb != null) cb.onDone(false);
                    return;
                }
                String bodyStr = resp.body() != null ? resp.body().string() : "";
                if (bodyStr.isEmpty()) {
                    if (manual) toast(app, "检查更新失败：响应为空");
                    log(app, "检查更新失败", "响应为空");
                    if (cb != null) cb.onDone(false);
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
                    log(app, "无新版本", "当前=" + current + " 最新=" + tag);
                    if (cb != null) cb.onDone(true);
                    return;
                }
                if (apkUrl.isEmpty()) {
                    if (manual) toast(app, "新版本 " + tag + " 未附带 APK 文件");
                    log(app, "新版本缺少APK", "tag=" + tag);
                    if (cb != null) cb.onDone(true);
                    return;
                }
                log(app, "发现新版本", "当前=" + current + " 最新=" + tag);
                final String fApkUrl = apkUrl;
                final String fTag = tag;
                MAIN.post(() -> showUpdateDialog(uiCtx, fTag, notes, fApkUrl));
                if (cb != null) cb.onDone(true);
            } catch (Exception e) {
                if (manual) toast(app, "检查更新失败: " + e.getMessage());
                log(app, "检查更新异常", e == null ? "null" : String.valueOf(e.getMessage()));
                if (cb != null) cb.onDone(false);
            }
        });
    }

    /** 统一写更新日志（日志页可查，方便定位"无弹窗/无反应"原因） */
    private static void log(Context ctx, String title, String content) {
        try { LogRecorder.info(ctx, "UPDATE", title, content); } catch (Exception ignore) {}
    }

    private static void showUpdateDialog(final Context ctx, final String version, final String notes, final String apkUrl) {
        try {
            // Activity 已销毁/正在结束时不弹窗，避免 BadTokenException
            if (ctx instanceof Activity) {
                Activity a = (Activity) ctx;
                if (a.isFinishing() || a.isDestroyed()) return;
            }
            boolean force = notes != null && notes.contains(FORCE_MARK);
            AlertDialog.Builder b = new AlertDialog.Builder(ctx)
                    .setTitle("发现新版本 " + version)
                    .setMessage((notes == null || notes.trim().isEmpty()) ? "是否立即下载更新？" : notes.trim())
                    .setPositiveButton("立即更新", (d, w) -> downloadAndInstall(ctx, apkUrl));
            if (force) {
                // 强制更新：不可取消、不提供“以后再说”
                b.setCancelable(false);
            } else {
                b.setNegativeButton("以后再说", null);
            }
            b.show();
        } catch (Exception e) {
            log(ctx, "更新弹窗失败", e == null ? "null" : String.valueOf(e.getMessage()));
        }
    }

    private static void downloadAndInstall(final Context ctx, final String apkUrl) {
        final ProgressDialog pd = new ProgressDialog(ctx);
        pd.setTitle("正在下载更新");
        pd.setMessage("请稍候...");
        pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        pd.setCancelable(false);
        pd.show();
        Threads.io().execute(() -> {
            File dir = new File(ctx.getCacheDir(), "updates");
            if (!dir.exists()) dir.mkdirs();
            File part = new File(dir, "app-update.apk.part");
            File apk = new File(dir, "app-update.apk");
            try {
                // 断点续传：上次未下完的部分继续，服务器返回 206 则追加写入
                long done = part.exists() ? part.length() : 0L;
                Request.Builder rb = new Request.Builder().url(apkUrl);
                if (done > 0) rb.header("Range", "bytes=" + done + "-");
                Response resp = HTTP.newCall(rb.build()).execute();
                if (resp == null || !resp.isSuccessful()) {
                    fail(pd, ctx, "下载失败（网络错误，稍后重试可断点续传）");
                    return;
                }
                long total;
                boolean append;
                if (resp.code() == 206) {
                    append = true;
                    total = parseContentRangeTotal(resp.header("Content-Range", ""));
                    long bodyLen = resp.body() != null ? resp.body().contentLength() : -1;
                    if (total <= done) total = bodyLen > 0 ? done + bodyLen : -1;
                } else {
                    // 服务器不支持断点，从头下载
                    append = false;
                    total = resp.body() != null ? resp.body().contentLength() : -1;
                    if (part.exists()) part.delete();
                    done = 0;
                }
                if (total <= 0) total = -1;
                long written = done;
                try (InputStream in = resp.body().byteStream();
                     FileOutputStream fos = new FileOutputStream(part, append)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        fos.write(buf, 0, n);
                        written += n;
                        if (total > 0) {
                            final int pct = (int) (written * 100 / total);
                            if (pct > 100) continue;
                            MAIN.post(() -> {
                                try {
                                    pd.setProgress(pct);
                                    pd.setMessage("已下载 " + pct + "%");
                                } catch (Exception ignore) {}
                            });
                        }
                    }
                    fos.flush();
                }
                // 下载完成：把 .part 改名为正式 APK（rename 失败时兜底复制）
                if (!part.renameTo(apk)) {
                    try (java.io.FileInputStream fin = new java.io.FileInputStream(part);
                         FileOutputStream fout = new FileOutputStream(apk)) {
                        byte[] b2 = new byte[8192];
                        int m;
                        while ((m = fin.read(b2)) > 0) fout.write(b2, 0, m);
                        fout.flush();
                    }
                    part.delete();
                }
                // 安装前签名校验：防止下载包被替换
                if (!verifyApkSignature(ctx, apk)) {
                    fail(pd, ctx, "更新包签名校验失败，已取消安装");
                    return;
                }
                MAIN.post(() -> {
                    try { pd.dismiss(); } catch (Exception ignore) {}
                    installApk(ctx, apk);
                });
            } catch (Exception e) {
                fail(pd, ctx, "下载失败: " + e.getMessage());
            }
        });
    }

    /**
     * 校验下载的 APK 签名与当前已安装包一致，防止被替换成恶意包。
     * 注意：Android 11+ 的 getPackageArchiveInfo 经常读不到下载包签名信息，
     * 此时无法比对 → 放行（系统安装器仍会强制签名一致性，不同签名无法覆盖安装）。
     * 只有能明确读到两侧签名且确实不一致时才拦截，避免误伤正常更新。
     */
    private static boolean verifyApkSignature(Context ctx, File apk) {
        try {
            PackageManager pm = ctx.getPackageManager();
            if (Build.VERSION.SDK_INT >= 28) {
                PackageInfo installed = pm.getPackageInfo(ctx.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
                PackageInfo archive = pm.getPackageArchiveInfo(apk.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
                if (installed == null || installed.signingInfo == null) return false;
                Signature[] a = installed.signingInfo.getApkContentsSigners();
                if (archive == null || archive.signingInfo == null) {
                    log(ctx, "签名校验放行", "系统读不到下载包签名，交由系统安装器校验");
                    return true;
                }
                return signaturesMatch(a, archive.signingInfo.getApkContentsSigners());
            } else {
                PackageInfo installed = pm.getPackageInfo(ctx.getPackageName(), PackageManager.GET_SIGNATURES);
                PackageInfo archive = pm.getPackageArchiveInfo(apk.getAbsolutePath(), PackageManager.GET_SIGNATURES);
                if (installed == null || installed.signatures == null) return false;
                if (archive == null || archive.signatures == null) {
                    log(ctx, "签名校验放行", "系统读不到下载包签名，交由系统安装器校验");
                    return true;
                }
                return signaturesMatch(installed.signatures, archive.signatures);
            }
        } catch (Exception e) {
            // 校验异常时不拦截，避免误伤正常更新（系统安装器仍会校验签名一致性）
            log(ctx, "签名校验放行", "校验异常: " + e.getMessage());
            return true;
        }
    }

    private static boolean signaturesMatch(Signature[] a, Signature[] b) {
        if (a == null || b == null || a.length == 0 || b.length == 0 || a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) {
            if (a[i] == null || b[i] == null) return false;
            if (!a[i].toCharsString().equals(b[i].toCharsString())) return false;
        }
        return true;
    }

    /** 从 Content-Range: bytes 0-99/1000 中解析总大小，解析失败返回 -1 */
    private static long parseContentRangeTotal(String header) {
        if (header == null) return -1L;
        int slash = header.lastIndexOf('/');
        if (slash < 0) return -1L;
        try {
            return Long.parseLong(header.substring(slash + 1).trim());
        } catch (Exception e) {
            return -1L;
        }
    }

    private static void fail(final ProgressDialog pd, final Context ctx, final String msg) {
        MAIN.post(() -> {
            try { pd.dismiss(); } catch (Exception ignore) {}
            toast(ctx, msg);
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
