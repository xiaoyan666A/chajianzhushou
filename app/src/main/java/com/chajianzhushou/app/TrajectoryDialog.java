package com.chajianzhushou.app;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import okhttp3.OkHttpClient;

/**
 * 包裹轨迹详情弹窗：点击查件卡片（非图片区域）弹出。
 * 数据来自 getStockBillLog（与兔喜官方app一致的轨迹时间线）。
 * UI 与 App 主题统一（卡片背景/圆角/配色）；每条显示 操作名/时间/快递名称/操作人/状态；
 * 仅"入库(12)"与"出库(50)"操作附带图片缩略图（点击可放大）。
 */
public class TrajectoryDialog {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public static void show(final Context ctx,
                            final DirectApiClient client,
                            final OkHttpClient httpClient,
                            final String billCode,
                            final String expressCompanyCode) {
        if (ctx == null || billCode == null || billCode.isEmpty() || client == null) return;
        final Dialog loading = buildBaseDialog(ctx);
        LinearLayout loadingBody = dialogBody(ctx);
        TextView msg = new TextView(ctx);
        msg.setText("正在加载轨迹...");
        msg.setTextColor(color(ctx, R.color.ink2));
        msg.setTextSize(15f);
        loadingBody.addView(msg);
        loading.setContentView(loadingBody);
        loading.show();

        Threads.io().execute(() -> {
            JSONArray logs = null;
            try {
                logs = client.getStockBillLogEntries(billCode, expressCompanyCode);
            } catch (Throwable ignore) {}
            final JSONArray result = logs;
            MAIN.post(() -> {
                try { loading.dismiss(); } catch (Exception ignore) {}
                if (result == null || result.length() == 0) {
                    Dialog empty = buildBaseDialog(ctx);
                    LinearLayout emptyBody = dialogBody(ctx);
                    TextView emptyMsg = new TextView(ctx);
                    emptyMsg.setText("暂无轨迹数据");
                    emptyMsg.setTextColor(color(ctx, R.color.ink2));
                    emptyMsg.setTextSize(15f);
                    emptyBody.addView(emptyMsg);
                    empty.setContentView(emptyBody);
                    empty.show();
                    return;
                }
                buildDialog(ctx, client, httpClient, billCode, result);
            });
        });
    }

    /** 与 App 统一的弹窗容器：无标题、透明窗口、卡片圆角背景 */
    private static Dialog buildBaseDialog(Context ctx) {
        Dialog d = new Dialog(ctx);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(android.graphics.Color.TRANSPARENT));
            w.setGravity(Gravity.CENTER);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
            lp.copyFrom(w.getAttributes());
            lp.width = (int) (ctx.getResources().getDisplayMetrics().widthPixels * 0.97f);
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            w.setAttributes(lp);
        }
        return d;
    }

    private static LinearLayout dialogBody(Context ctx) {
        LinearLayout body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setBackgroundResource(R.drawable.bg_settings_card);
        int pad = dp(ctx, 20);
        body.setPadding(pad, pad, pad, pad);
        return body;
    }

    private static void buildDialog(Context ctx, DirectApiClient client, OkHttpClient httpClient,
                                    String billCode, JSONArray logs) {
        LinearLayout body = dialogBody(ctx);

        // 标题
        TextView title = new TextView(ctx);
        title.setText("包裹轨迹");
        title.setTextColor(color(ctx, R.color.ink));
        title.setTextSize(18f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        body.addView(title);

        // 单号
        TextView billTv = new TextView(ctx);
        billTv.setText("单号：" + billCode);
        billTv.setTextColor(color(ctx, R.color.muted));
        billTv.setTextSize(13f);
        billTv.setTypeface(Typeface.MONOSPACE);
        billTv.setPadding(0, dp(ctx, 4), 0, dp(ctx, 12));
        body.addView(billTv);

        // 时间线内容
        ScrollView sv = new ScrollView(ctx);
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        sv.addView(root);
        body.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        for (int i = 0; i < logs.length(); i++) {
            JSONObject item = logs.optJSONObject(i);
            if (item == null) continue;
            root.addView(makeEntry(ctx, client, httpClient, billCode, item, i, logs.length()));
        }

        // 关闭按钮
        TextView close = new TextView(ctx);
        close.setText("关闭");
        close.setTextColor(color(ctx, R.color.accent));
        close.setTextSize(15f);
        close.setTypeface(Typeface.DEFAULT_BOLD);
        close.setGravity(Gravity.CENTER);
        close.setBackgroundResource(R.drawable.bg_btn_accent);
        int closePadV = dp(ctx, 12);
        close.setPadding(0, closePadV, 0, closePadV);
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        closeLp.topMargin = dp(ctx, 12);
        close.setLayoutParams(closeLp);
        body.addView(close);

        Dialog d = buildBaseDialog(ctx);
        d.setContentView(body);
        close.setOnClickListener(v -> d.dismiss());
        d.show();
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

        // 操作名
        TextView name = new TextView(ctx);
        String typeName = item.optString("stockLogTypeName", "");
        name.setText(typeName.length() > 0 ? typeName : item.optString("stockLogType", "操作"));
        name.setTextColor(color(ctx, R.color.ink));
        name.setTextSize(15f);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        content.addView(name);

        // 时间
        String time = item.optString("gmtCreated", "");
        if (time.length() > 0) {
            TextView timeTv = new TextView(ctx);
            timeTv.setText(time);
            timeTv.setTextColor(color(ctx, R.color.muted));
            timeTv.setTextSize(12f);
            content.addView(timeTv);
        }

        // 快递名称 / 操作人 / 状态（有值才显示；备注不展示）
        StringBuilder sub = new StringBuilder();
        String platform = item.optString("platformName", "");
        if (platform.length() > 0) sub.append("快递：").append(platform);
        String staff = item.optString("staffName", "");
        if (staff.length() > 0) {
            if (sub.length() > 0) sub.append('\n');
            sub.append("操作人：").append(staff);
        }
        String statusName = item.optString("pushStatusName", "");
        if (statusName.length() > 0) {
            if (sub.length() > 0) sub.append('\n');
            sub.append("状态：").append(statusName);
        }
        if (sub.length() > 0) {
            TextView subTv = new TextView(ctx);
            subTv.setText(sub.toString());
            subTv.setTextColor(color(ctx, R.color.ink2));
            subTv.setTextSize(12f);
            content.addView(subTv);
        }

        // 仅"入库(12)"与"出库(50)"操作附带图片
        String typeCode = item.optString("stockLogTypeCode", "");
        boolean withPhoto = "12".equals(typeCode) || "50".equals(typeCode);
        final String image = withPhoto ? item.optString("image", "") : "";
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
