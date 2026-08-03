package com.chajianzhushou.app;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import org.json.JSONArray;
import org.json.JSONObject;

import okhttp3.OkHttpClient;

/**
 * 包裹轨迹详情弹窗：点击查件卡片（非图片区域）弹出，
 * 数据来自 getStockBillLog（与兔喜官方app一致的轨迹时间线）。
 * 每条操作显示：操作名、时间、操作人/状态/备注，操作带图时异步加载缩略图（点击可放大）。
 */
public class TrajectoryDialog {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public static void show(final Context ctx,
                            final DirectApiClient client,
                            final OkHttpClient httpClient,
                            final String billCode,
                            final String expressCompanyCode) {
        if (ctx == null || billCode == null || billCode.isEmpty() || client == null) return;
        final AlertDialog loading = new AlertDialog.Builder(ctx)
                .setTitle("包裹轨迹")
                .setMessage("正在加载轨迹...")
                .setCancelable(true)
                .setNegativeButton("关闭", null)
                .show();

        new Thread(() -> {
            JSONArray logs = null;
            try {
                logs = client.getStockBillLogEntries(billCode, expressCompanyCode);
            } catch (Throwable ignore) {}
            final JSONArray result = logs;
            MAIN.post(() -> {
                try { loading.dismiss(); } catch (Exception ignore) {}
                if (result == null || result.length() == 0) {
                    try {
                        new AlertDialog.Builder(ctx)
                                .setTitle("包裹轨迹")
                                .setMessage("暂无轨迹数据")
                                .setNegativeButton("关闭", null)
                                .show();
                    } catch (Throwable ignore) {}
                    return;
                }
                buildDialog(ctx, client, httpClient, billCode, result);
            });
        }, "trajectory").start();
    }

    private static void buildDialog(Context ctx, DirectApiClient client, OkHttpClient httpClient,
                                    String billCode, JSONArray logs) {
        ScrollView sv = new ScrollView(ctx);
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(ctx, 16), dp(ctx, 8), dp(ctx, 16), dp(ctx, 12));
        sv.addView(root);

        TextView billTv = new TextView(ctx);
        billTv.setText("单号：" + billCode);
        billTv.setTextColor(color(ctx, R.color.muted));
        billTv.setTextSize(13f);
        billTv.setTypeface(Typeface.MONOSPACE);
        billTv.setPadding(0, 0, 0, dp(ctx, 10));
        root.addView(billTv);

        for (int i = 0; i < logs.length(); i++) {
            JSONObject item = logs.optJSONObject(i);
            if (item == null) continue;
            root.addView(makeEntry(ctx, client, httpClient, billCode, item, i, logs.length()));
        }

        try {
            new AlertDialog.Builder(ctx)
                    .setTitle("包裹轨迹")
                    .setView(sv)
                    .setNegativeButton("关闭", null)
                    .show();
        } catch (Throwable ignore) {}
    }

    private static View makeEntry(Context ctx, DirectApiClient client, OkHttpClient httpClient,
                                  String billCode, JSONObject item, int idx, int total) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(ctx, 2), 0, dp(ctx, 2));

        // 左侧时间线：圆点 + 连线
        LinearLayout timeline = new LinearLayout(ctx);
        timeline.setOrientation(LinearLayout.VERTICAL);
        timeline.setGravity(Gravity.CENTER_HORIZONTAL);
        View dot = new View(ctx);
        int dotSize = dp(ctx, 10);
        dot.setBackgroundResource(R.drawable.bg_status_pending);
        timeline.addView(dot, new LinearLayout.LayoutParams(dotSize, dotSize));
        if (idx < total - 1) {
            View line = new View(ctx);
            line.setBackgroundColor(color(ctx, R.color.hair2));
            timeline.addView(line, new LinearLayout.LayoutParams(dp(ctx, 2), ViewGroup.LayoutParams.MATCH_PARENT));
        }
        row.addView(timeline, new LinearLayout.LayoutParams(dp(ctx, 24), ViewGroup.LayoutParams.MATCH_PARENT));

        // 右侧内容
        LinearLayout content = new LinearLayout(ctx);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(ctx, 8), 0, 0, 0);
        row.addView(content, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView name = new TextView(ctx);
        String typeName = item.optString("stockLogTypeName", "");
        name.setText(typeName.length() > 0 ? typeName : item.optString("stockLogType", "操作"));
        name.setTextColor(color(ctx, R.color.ink));
        name.setTextSize(15f);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        content.addView(name);

        String time = item.optString("gmtCreated", "");
        if (time.length() > 0) {
            TextView timeTv = new TextView(ctx);
            timeTv.setText(time);
            timeTv.setTextColor(color(ctx, R.color.muted));
            timeTv.setTextSize(12f);
            content.addView(timeTv);
        }

        StringBuilder sub = new StringBuilder();
        String staff = item.optString("staffName", "");
        if (staff.length() > 0) sub.append("操作人：").append(staff);
        String statusName = item.optString("pushStatusName", "");
        if (statusName.length() > 0) {
            if (sub.length() > 0) sub.append('\n');
            sub.append("状态：").append(statusName);
        }
        String remark = item.optString("remark", "");
        if (remark.length() > 0) {
            if (sub.length() > 0) sub.append('\n');
            sub.append("备注：").append(remark);
        }
        if (sub.length() > 0) {
            TextView subTv = new TextView(ctx);
            subTv.setText(sub.toString());
            subTv.setTextColor(color(ctx, R.color.ink2));
            subTv.setTextSize(12f);
            content.addView(subTv);
        }

        // 该操作带图：异步解析并加载缩略图，点击可放大预览
        final String image = item.optString("image", "");
        if (image != null && image.length() > 0) {
            final ImageView iv = new ImageView(ctx);
            int size = dp(ctx, 64);
            LinearLayout.LayoutParams ivLp = new LinearLayout.LayoutParams(size, size);
            ivLp.topMargin = dp(ctx, 6);
            iv.setLayoutParams(ivLp);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackgroundResource(R.drawable.bg_pkg_image);
            iv.setClipToOutline(true);
            iv.setOnClickListener(v -> {
                Object t = iv.getTag(R.id.image_loader_tag);
                String url = (t == null) ? "" : t.toString();
                if (url.length() > 0) {
                    ImagePreviewDialog.show(ctx, url, billCode, httpClient);
                }
            });
            content.addView(iv);
            try {
                client.resolveImageUrl(billCode, image, new DirectApiClient.ImageUrlCallback() {
                    @Override public void onUrl(String url) {
                        if (url == null || url.length() == 0) return;
                        MAIN.post(() -> {
                            try {
                                if (httpClient != null) {
                                    ImageLoader.with(httpClient).load(url, iv, R.drawable.bg_image_placeholder);
                                }
                            } catch (Throwable ignore) {}
                        });
                    }
                    @Override public void onError(String error) {}
                });
            } catch (Throwable ignore) {}
        }

        return row;
    }

    private static int color(Context ctx, int resId) {
        return ctx.getResources().getColor(resId, ctx.getTheme());
    }

    private static int dp(Context ctx, float v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }
}
