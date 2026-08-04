package com.chajianzhushou.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import androidx.cardview.widget.CardView;
import okhttp3.OkHttpClient;

/**
 * 包裹卡片构建器：从 QueryFragment 拆出的卡片视图构建、超时件闪烁、行/chip 辅助。
 * 图片加载、出入库对比、预览、轨迹弹窗等宿主能力通过 Host 接口回抛给 Fragment。
 */
public class PackageCardFactory {

    /** 宿主交互接口 */
    public interface Host {
        /** 超时件判定（remark=超时出库 且在 N 天内） */
        boolean isTimeoutPackage(JSONObject item);
        /** 加载卡片图片：有完整 URL 直接加载，只有原始路径则按需解析；返回当前解析到的 URL */
        String loadCardImage(ImageView iv, String trackingNumber, String imageUrl, String rawImgPath);
        /** 准备出入库对比照片 */
        void prepareComparePhoto(String trackingNumber, JSONObject item, String rawImgPath);
        /** 点击图片放大预览（由宿主处理全量翻页/对比切换） */
        void showImagePreview(ImageView iv);
        /** 长按卡片弹出轨迹详情 */
        void showTrajectory(String trackingNumber, String expressCompanyCode);
        /** Toast 提示 */
        void toast(String msg);
    }

    private final Context context; // 必须是 Activity 上下文（带主题）
    private final Host host;
    // 超时件闪烁任务：单号 → Runnable
    private final Map<String, Runnable> timeoutBlinkMap = new HashMap<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public PackageCardFactory(Context context, Host host) {
        this.context = context;
        this.host = host;
    }

    /** 创建单张卡片视图（网格/列表通用；不加入容器） */
    public CardView createCard(JSONObject item, boolean vertical, int spanCount) {
        final android.content.res.Resources res = context.getResources();
        final int dp14 = res.getDimensionPixelSize(R.dimen.pad_card_h);
        final int dp12 = res.getDimensionPixelSize(R.dimen.spacing_lg);
        final int dp10 = res.getDimensionPixelSize(R.dimen.card_margin_bottom);
        final int dp8  = res.getDimensionPixelSize(R.dimen.spacing_md);
        final int dp6  = res.getDimensionPixelSize(R.dimen.spacing_sm);

        CardView card = new CardView(context);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp12;
        card.setLayoutParams(lp);
        card.setRadius(res.getDimension(R.dimen.radius_2xl));
        card.setCardElevation(0);
        card.setBackgroundResource(R.drawable.bg_pkg_card);

        String trackingNumber = firstNonEmpty(
                item.optString("billCode", ""),
                item.optString("trackingNumber", ""),
                item.optString("waybillCode", ""));
        String recipient = firstNonEmpty(
                item.optString("recipientName", ""),
                item.optString("receiveMan", ""),
                item.optString("receiver", ""));
        String pickupCode = firstNonEmpty(
                item.optString("pickupCode", ""),
                item.optString("takeCode", ""));
        String courier = firstNonEmpty(
                item.optString("courier", ""),
                item.optString("express", ""),
                item.optString("expressCompanyName", ""));
        String imageUrl = firstNonEmpty(
                item.optString("imageUrl", ""),
                item.optString("imgUrl", ""),
                item.optString("picture", ""),
                item.optString("pic", ""),
                item.optString("photo", ""));
        // 直连模式：查询结果只带原始图片路径，URL 由渲染时按需解析（随滚动分批）
        String rawImgPath = firstNonEmpty(
                item.optString("rawImgPath", ""),
                item.optString("fileImgPath", ""),
                item.optString("inSignImg", ""),
                item.optString("imgName", ""));
        String arrivedAt = firstNonEmpty(
                item.optString("arrivedAt", ""),
                item.optString("time", ""),
                item.optString("createTime", ""));
        // 出库时间（仅 delivered / 已出库 状态下优先展示）
        String outboundTime = firstNonEmpty(
                item.optString("outboundTime", ""),
                item.optString("deliveryTime", ""),
                item.optString("deliveredTime", ""),
                item.optString("signedTime", ""),
                item.optString("signTime", ""),
                item.optString("outTime", ""),
                item.optString("outboundAt", ""),
                item.optString("outDate", ""),
                item.optString("pickupTime", ""));
        String status = item.optString("status", "pending");
        boolean timeout = "delivered".equals(status) && host.isTimeoutPackage(item);
        // 普通"已出库"卡片整体稍微调暗，与待取件区分；超时件保持高亮（另有标注效果）
        if ("delivered".equals(status) && !timeout) {
            card.setAlpha(0.8f);
        }
        String mobile = firstNonEmpty(
                item.optString("receiveManMobile", ""),
                item.optString("phone", ""));

        int ink = context.getResources().getColor(R.color.ink, context.getTheme());
        int ink2 = context.getResources().getColor(R.color.ink2, context.getTheme());
        int muted = context.getResources().getColor(R.color.muted, context.getTheme());
        int accent = context.getResources().getColor(R.color.accent, context.getTheme());
        int danger = context.getResources().getColor(R.color.danger, context.getTheme());
        int champagne = context.getResources().getColor(R.color.champagne, context.getTheme());

        card.setTag(R.id.btn_query, trackingNumber);
        card.setTag(R.id.tag_pkg_rawpath, rawImgPath);

        // Root container
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(vertical ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        root.setGravity(vertical ? Gravity.NO_GRAVITY : Gravity.CENTER_VERTICAL);
        // 卡片水平内边距 14dp→9.5dp（两侧合计省出 9dp），单号等内容可用宽度 +9dp
        int padSide = (int) (res.getDisplayMetrics().density * 9.5f + 0.5f);
        root.setPadding(padSide, dp14, padSide, vertical ? dp10 : dp14);
        card.addView(root);

        // ===== Image =====
        ImageView iv = new ImageView(context);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackgroundResource(R.drawable.bg_pkg_image);
        // 图片内容按圆角背景裁切
        iv.setClipToOutline(true);
        if (vertical) {
            // 根据跨度调整图片高度：跨度越大高度越小（屏幕越宽卡片越小）
            int baseImgH = res.getDimensionPixelSize(R.dimen.grid_img_height);
            int imgH;
            if (spanCount >= 3)       imgH = baseImgH;
            else if (spanCount == 2)  imgH = (int) (baseImgH * 1.25f);
            else                       imgH = (int) (baseImgH * 1.6f);
            LinearLayout.LayoutParams imgLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, imgH);
            imgLp.bottomMargin = dp12;
            iv.setLayoutParams(imgLp);
        } else {
            int imgSize = res.getDimensionPixelSize(R.dimen.grid_img_height);
            LinearLayout.LayoutParams imgLp = new LinearLayout.LayoutParams(imgSize, imgSize);
            imgLp.rightMargin = dp14;
            imgLp.gravity = Gravity.TOP;
            iv.setLayoutParams(imgLp);
        }
        // 图片加载：有完整URL（服务器模式）直接加载；只有原始路径（直连模式）按需异步解析后再加载
        host.loadCardImage(iv, trackingNumber, imageUrl, rawImgPath);
        // 出入库照片对比：准备"另一张照片"（入库照/出库照），供预览大图切换
        host.prepareComparePhoto(trackingNumber, item, rawImgPath);
        // 点击图片放大预览（点击时读取 ImageView 当前 URL，自动刷新换新照片后预览到的也是最新图）
        iv.setClickable(true);
        iv.setFocusable(true);
        iv.setOnClickListener(v -> {
            try {
                host.showImagePreview(iv);
            } catch (Throwable ignore) {}
        });
        root.addView(iv);

        // ===== Info section =====
        LinearLayout info = new LinearLayout(context);
        info.setOrientation(LinearLayout.VERTICAL);
        if (vertical) {
            // 网格模式：让 info 区自动撑开剩余空间，这样 statusTag 始终贴底，
            // 同行卡片即使内容高度不同，底部的状态 tag 也能保持水平对齐
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f);
            info.setLayoutParams(ilp);
        } else {
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            ilp.rightMargin = dp12;
            info.setLayoutParams(ilp);
        }

        // 单号行标签与值的间距设为 1.5dp（取消 40dp 最小宽度后按需求微调），其余行保持 4dp
        int gap2 = (int) (context.getResources().getDisplayMetrics().density * 1.5f + 0.5f);
        int dp5 = (int) (context.getResources().getDisplayMetrics().density * 5 + 0.5f);
        LinearLayout billRow = makeLabelValueRow(context, "单号", trackingNumber, ink2, champagne, ink, true, dp6, 14, 16, true);
        setLabelGap(billRow, gap2);
        // 取消单号标签的 40dp 最小宽度，数字真正紧贴"单号"（这是之前间距没变小的主因）
        setLabelMinWidth(billRow, 0);
        // 单号后面预留 5dp，避免最后一位数字紧贴行尾导致显示不全
        setValueRightPadding(billRow, dp5);
        info.addView(billRow);
        makeValueClickableToCopy(billRow, context, "单号", trackingNumber); // 点击单号直接复制
        // 收件人(姓名+手机号)：网格窄卡下强制单行省略号，避免折行撑高卡片导致同行不齐；
        // 收件人为空/未知时不显示姓名（有手机号则只显示手机号，都没有则整行隐藏）
        StringBuilder who = new StringBuilder();
        if (!isUnknownText(recipient)) who.append(recipient);
        if (mobile.length() > 0) {
            if (who.length() > 0) who.append("  ");
            who.append(normalizeMaskedMobile(mobile));
        }
        if (who.length() > 0) {
            addLabelValueRow(info, "收件人", who.toString(), ink2, ink, ink, false, dp6, 14, 16, true);
        }

        // 无论 pickUpCode 是否为空都固定显示一行，保证 info 区行数一致
        LinearLayout pickRow = makeLabelValueRow(context, "取件码", pickupCode,
                ink2, accent, ink, false, dp6, 14, 20, true);
        try {
            View val = pickRow.getChildAt(1);
            if (val instanceof TextView) {
                if (pickupCode.length() > 0) {
                    ((TextView) val).setTypeface(Typeface.MONOSPACE);
                    ((TextView) val).setTypeface(((TextView) val).getTypeface(), Typeface.BOLD);
                }
            }
        } catch (Throwable ignore) {}
        info.addView(pickRow);
        makeValueClickableToCopy(pickRow, context, "取件码", pickupCode); // 点击取件码直接复制

        // 快递 + 时间：上下两行显示，避免网格模式/小屏时水平排列被截断
        LinearLayout metaWrap = new LinearLayout(context);
        metaWrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams mwLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mwLp.topMargin = dp8;
        metaWrap.setLayoutParams(mwLp);
        // 第一行：快递 chip（固定一行）
        metaWrap.addView(makeMetaChip(context, courier.length() > 0 ? courier : "—", muted, ink2, false));
        // 第二行：时间（按状态区分：入库时间 / 出库时间 + 前缀 + 值，允许折行）
        boolean isDelivered = "delivered".equals(status);
        String timeLabel = isDelivered ? "出库 " : "入库 ";
        String rawTime = isDelivered ? (outboundTime.length() > 0 ? outboundTime : arrivedAt) : arrivedAt;
        String timeDisplay = timeLabel + QueryFragment.formatDisplayTime(rawTime);
        TextView timeChip = new TextView(context);
        timeChip.setText(timeDisplay);
        timeChip.setTextColor(ink2);
        timeChip.setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.chip_text_size));
        timeChip.setBackgroundResource(R.drawable.bg_btn_back);
        int chipPad = res.getDimensionPixelSize(R.dimen.chip_padding_h);
        int chipPadV = res.getDimensionPixelSize(R.dimen.chip_padding_v);
        timeChip.setPadding(chipPad, chipPadV, chipPad, chipPadV);
        // 网格/列表均强制单行：避免两行撑开卡片高度造成同行不对齐
        timeChip.setSingleLine(true);
        timeChip.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tLp.topMargin = dp6;
        timeChip.setLayoutParams(tLp);
        if (timeout) {
            // 超时件：出库时间外圈加"流水灯"黄色流动边框（③）
            metaWrap.addView(wrapTimeWithFlowBorder(context, timeChip, tLp));
        } else {
            metaWrap.addView(timeChip);
        }
        info.addView(metaWrap);

        // ===== Status tag =====
        TextView statusTag = new TextView(context);
        boolean pending = "pending".equals(status);
        // 记录待取状态 + 应用"显示已出库激活时"的绿色边框标识
        card.setTag(R.id.tag_pkg_pending, pending);
        card.setTag(R.id.tag_pkg_status, status == null ? "" : status);
        QueryFragment.applyCardPendingBorder(card, pending);
        statusTag.setText(pending ? "待取件" : ("delivered".equals(status) ? "已出库" : status));
        statusTag.setTextColor(pending ? accent : danger);
        statusTag.setBackgroundResource(pending ? R.drawable.bg_status_pending : R.drawable.bg_status_delivered);
        statusTag.setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.chip_text_size));
        statusTag.setTypeface(Typeface.DEFAULT_BOLD);
        statusTag.setPadding(res.getDimensionPixelSize(R.dimen.chip_padding_h), res.getDimensionPixelSize(R.dimen.chip_padding_v), res.getDimensionPixelSize(R.dimen.chip_padding_h), res.getDimensionPixelSize(R.dimen.chip_padding_v));

        // 状态区：待取件/已出库文字 + 超时件黄色"超时出库"标签（②）
        LinearLayout statusBox = new LinearLayout(context);
        statusBox.setOrientation(vertical ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        statusBox.setGravity(vertical ? Gravity.CENTER_HORIZONTAL : Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (vertical) stLp.topMargin = dp10;
        statusBox.setLayoutParams(stLp);
        statusBox.addView(statusTag);
        if (timeout) {
            TextView timeoutTag = makeTimeoutTag(context, res, vertical, daysSinceOutbound(outboundTime));
            LinearLayout.LayoutParams tagLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (vertical) tagLp.leftMargin = res.getDimensionPixelSize(R.dimen.spacing_sm);
            else tagLp.topMargin = res.getDimensionPixelSize(R.dimen.spacing_sm);
            statusBox.addView(wrapTagWithFlowBorder(context, timeoutTag, tagLp));
        }
        root.addView(info);
        root.addView(statusBox);

        // 超时件：卡片黄色边框缓慢闪烁（①）
        if (timeout) {
            startBlink(card, trackingNumber);
        }

        // 长按卡片弹出包裹轨迹详情
        card.setOnLongClickListener(v -> {
            try {
                host.showTrajectory(trackingNumber, QueryFragment.resolveExpressCompanyCode(item));
            } catch (Throwable ignore) {}
            return true;
        });

        return card;
    }

    /** 创建单张卡片并加入容器（列表模式/差分追加用） */
    public void createAndAdd(LinearLayout container, JSONObject item, boolean vertical) {
        if (container == null) return;
        try {
            final android.content.res.Resources res = context.getResources();
            final float d = res.getDisplayMetrics().density;
            int dp6 = res.getDimensionPixelSize(R.dimen.spacing_sm), dp8 = res.getDimensionPixelSize(R.dimen.spacing_md), dp10 = res.getDimensionPixelSize(R.dimen.card_margin_bottom);
            int dp12 = res.getDimensionPixelSize(R.dimen.spacing_lg), dp14 = res.getDimensionPixelSize(R.dimen.pad_card_h);

            // Multi-field extraction
            String trackingNumber = firstNonEmpty(
                    item.optString("billCode", ""),
                    item.optString("trackingNumber", ""),
                    item.optString("waybillCode", ""));
            String recipient = firstNonEmpty(
                    item.optString("receiveMan", ""),
                    item.optString("recipientName", ""),
                    item.optString("receiver", ""));
            String pickupCode = firstNonEmpty(
                    item.optString("pickupCode", ""),
                    item.optString("takeCode", ""),
                    item.optString("code", ""));
            String courier = firstNonEmpty(
                    item.optString("express", ""),
                    item.optString("expressCompany", ""),
                    item.optString("courier", ""));
            String imageUrl = firstNonEmpty(
                    item.optString("imageUrl", ""),
                    item.optString("imgUrl", ""),
                    item.optString("picture", ""),
                    item.optString("pic", ""),
                    item.optString("photo", ""));
            // 直连模式：查询结果只带原始图片路径，URL 由渲染时按需解析（随滚动分批）
            String rawImgPath = firstNonEmpty(
                    item.optString("rawImgPath", ""),
                    item.optString("fileImgPath", ""),
                    item.optString("inSignImg", ""),
                    item.optString("imgName", ""));
            String arrivedAt = firstNonEmpty(
                    item.optString("arrivedAt", ""),
                    item.optString("time", ""),
                    item.optString("createTime", ""));
            // 出库时间（delivered 时优先展示）
            String outboundTime = firstNonEmpty(
                    item.optString("outboundTime", ""),
                    item.optString("deliveryTime", ""),
                    item.optString("deliveredTime", ""),
                    item.optString("signedTime", ""),
                    item.optString("signTime", ""),
                    item.optString("outTime", ""),
                    item.optString("outboundAt", ""),
                    item.optString("outDate", ""),
                    item.optString("pickupTime", ""));
            String status = item.optString("status", "pending");
            boolean timeout = "delivered".equals(status) && host.isTimeoutPackage(item);
            String mobile = firstNonEmpty(
                    item.optString("receiveManMobile", ""),
                    item.optString("phone", ""));

            int ink = context.getResources().getColor(R.color.ink, context.getTheme());
            int ink2 = context.getResources().getColor(R.color.ink2, context.getTheme());
            int muted = context.getResources().getColor(R.color.muted, context.getTheme());
            int accent = context.getResources().getColor(R.color.accent, context.getTheme());
            int danger = context.getResources().getColor(R.color.danger, context.getTheme());
            int champagne = context.getResources().getColor(R.color.champagne, context.getTheme());

            // CardView outer
            CardView card = new CardView(context);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp12;
            card.setLayoutParams(lp);
            card.setRadius(res.getDimension(R.dimen.radius_2xl));
            card.setCardElevation(0);
            card.setBackgroundResource(R.drawable.bg_pkg_card);
            // 普通"已出库"卡片整体稍微调暗，与待取件区分；超时件保持高亮（另有标注效果）
            if ("delivered".equals(status) && !timeout) {
                card.setAlpha(0.8f);
            }

            // Root container
            LinearLayout root = new LinearLayout(context);
            root.setOrientation(vertical ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
            root.setGravity(vertical ? Gravity.NO_GRAVITY : Gravity.CENTER_VERTICAL);
            // 卡片水平内边距 14dp→9.5dp（两侧合计省出 9dp），单号等内容可用宽度 +9dp
            int padSide = (int) (res.getDisplayMetrics().density * 9.5f + 0.5f);
            root.setPadding(padSide, dp14, padSide, vertical ? dp10 : dp14);
            card.addView(root);

            // ===== Image =====
            ImageView iv = new ImageView(context);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackgroundResource(R.drawable.bg_pkg_image);
            // 图片内容按圆角背景裁切
            iv.setClipToOutline(true);
            if (vertical) {
                int imgW = ViewGroup.LayoutParams.MATCH_PARENT;
                int imgH = (int) (res.getDimensionPixelSize(R.dimen.grid_img_height) * 1.25f);
                LinearLayout.LayoutParams imgLp = new LinearLayout.LayoutParams(imgW, imgH);
                imgLp.bottomMargin = dp12;
                iv.setLayoutParams(imgLp);
            } else {
                int imgSize = res.getDimensionPixelSize(R.dimen.grid_img_height);
                LinearLayout.LayoutParams imgLp = new LinearLayout.LayoutParams(imgSize, imgSize);
                imgLp.rightMargin = dp14;
                imgLp.gravity = Gravity.TOP;
                iv.setLayoutParams(imgLp);
            }
            // 图片加载：有完整URL（服务器模式）直接加载；只有原始路径（直连模式）按需异步解析后再加载
            host.loadCardImage(iv, trackingNumber, imageUrl, rawImgPath);
            // 出入库照片对比：准备"另一张照片"（入库照/出库照），供预览大图切换
            host.prepareComparePhoto(trackingNumber, item, rawImgPath);
            // 点击图片放大预览：使用 allImageUrls/allTrackingNos 全量列表，支持跨包裹上下张翻页
            iv.setClickable(true);
            iv.setFocusable(true);
            iv.setOnClickListener(v -> {
                try {
                    host.showImagePreview(iv);
                } catch (Throwable ignore) {}
            });
            root.addView(iv);

            // ===== Info section =====
            LinearLayout info = new LinearLayout(context);
            info.setOrientation(LinearLayout.VERTICAL);
            if (vertical) {
                // 网格模式：info 区自动撑开剩余空间，statusTag 贴底（同行对齐）
                LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f);
                info.setLayoutParams(ilp);
            } else {
                LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                ilp.rightMargin = dp12;
                info.setLayoutParams(ilp);
            }

            // Bill code row
            // 单号行标签与值的间距设为 1.5dp（取消 40dp 最小宽度后按需求微调），其余行保持 4dp
            int gap2 = (int) (context.getResources().getDisplayMetrics().density * 1.5f + 0.5f);
            int dp5 = (int) (context.getResources().getDisplayMetrics().density * 5 + 0.5f);
            LinearLayout billRow = makeLabelValueRow(context, "单号", trackingNumber, ink2, champagne, ink, true, dp6, 14, 16, true);
            setLabelGap(billRow, gap2);
            // 取消单号标签的 40dp 最小宽度，数字真正紧贴"单号"（这是之前间距没变小的主因）
            setLabelMinWidth(billRow, 0);
            // 单号后面预留 5dp，避免最后一位数字紧贴行尾导致显示不全
            setValueRightPadding(billRow, dp5);
            info.addView(billRow);
            makeValueClickableToCopy(billRow, context, "单号", trackingNumber); // 点击单号直接复制

            // Recipient row：网格下单行省略，避免折行导致同行卡片高度不齐；
            // 收件人为空/未知时不显示姓名（有手机号则只显示手机号，都没有则整行隐藏）
            StringBuilder who = new StringBuilder();
            if (!isUnknownText(recipient)) who.append(recipient);
            if (mobile.length() > 0) {
                if (who.length() > 0) who.append("  ");
                who.append(normalizeMaskedMobile(mobile));
            }
            if (who.length() > 0) {
                addLabelValueRow(info, "收件人", who.toString(), ink2, ink, ink, false, dp6, 14, 16, true);
            }

            // Pickup code row（vertical 模式强制固定一行，保证信息区高度一致）
            LinearLayout pickRow = makeLabelValueRow(context, "取件码", pickupCode,
                    ink2, accent, ink, false, dp6, 14, 20, true);
            try {
                View val = pickRow.getChildAt(1);
                if (val instanceof TextView) {
                    if (pickupCode.length() > 0) {
                        ((TextView) val).setTypeface(Typeface.MONOSPACE);
                        ((TextView) val).setTypeface(((TextView) val).getTypeface(), Typeface.BOLD);
                    }
                }
            } catch (Throwable ignore) {}
            info.addView(pickRow);
            makeValueClickableToCopy(pickRow, context, "取件码", pickupCode); // 点击取件码直接复制

            // Meta row: courier(上) + arrivedAt(下) 各自占一行，避免时间被水平排列截断
            LinearLayout metaWrap = new LinearLayout(context);
            metaWrap.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams mwLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            mwLp.topMargin = dp8;
            metaWrap.setLayoutParams(mwLp);
            metaWrap.addView(makeMetaChip(context, courier.length() > 0 ? courier : "—", muted, ink2, false));
            // 时间：delivered 状态下显示"出库时间"（优先取outboundTime，没取到回退arrivedAt），其他显示"入库时间"
            boolean delivered2 = "delivered".equals(status);
            String timeLabel2 = delivered2 ? "出库 " : "入库 ";
            String rawTime2 = delivered2 ? (outboundTime.length() > 0 ? outboundTime : arrivedAt) : arrivedAt;
            String timeDisplay2 = timeLabel2 + QueryFragment.formatDisplayTime(rawTime2);
            TextView timeChip = new TextView(context);
            timeChip.setText(timeDisplay2);
            timeChip.setTextColor(ink2);
            timeChip.setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.chip_text_size));
            timeChip.setBackgroundResource(R.drawable.bg_btn_back);
            int cp2 = res.getDimensionPixelSize(R.dimen.chip_padding_h), cv2 = res.getDimensionPixelSize(R.dimen.chip_padding_v);
            timeChip.setPadding(cp2, cv2, cp2, cv2);
            // 强制单行省略：避免两行撑开卡片高度，造成同行卡片不齐
            timeChip.setSingleLine(true);
            timeChip.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams tLp2 = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tLp2.topMargin = dp6;
            timeChip.setLayoutParams(tLp2);
            if (timeout) {
                // 超时件：出库时间外圈加"流水灯"黄色流动边框（③）
                metaWrap.addView(wrapTimeWithFlowBorder(context, timeChip, tLp2));
            } else {
                metaWrap.addView(timeChip);
            }
            info.addView(metaWrap);

            // ===== Status tag =====
            TextView statusTag = new TextView(context);
            boolean pending = "pending".equals(status);
            // 记录待取状态 + 应用"显示已出库激活时"的绿色边框标识
            card.setTag(R.id.tag_pkg_pending, pending);
            QueryFragment.applyCardPendingBorder(card, pending);
            statusTag.setText(pending ? "待取件" : ("delivered".equals(status) ? "已出库" : status));
            statusTag.setTextColor(pending ? accent : danger);
            statusTag.setBackgroundResource(pending ? R.drawable.bg_status_pending : R.drawable.bg_status_delivered);
            statusTag.setTextSize(14f);
            statusTag.setTypeface(Typeface.DEFAULT_BOLD);
            statusTag.setPadding((int) (12 * d + 0.5f), (int) (6 * d + 0.5f), (int) (12 * d + 0.5f), (int) (6 * d + 0.5f));

            // 状态区：待取件/已出库文字 + 超时件黄色"超时出库"标签（②）
            LinearLayout statusBox = new LinearLayout(context);
            statusBox.setOrientation(vertical ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
            statusBox.setGravity(vertical ? Gravity.CENTER_HORIZONTAL : Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (vertical) stLp.topMargin = dp10;
            statusBox.setLayoutParams(stLp);
            statusBox.addView(statusTag);
            if (timeout) {
                TextView timeoutTag = makeTimeoutTag(context, res, vertical, daysSinceOutbound(outboundTime));
                LinearLayout.LayoutParams tagLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (vertical) tagLp.leftMargin = res.getDimensionPixelSize(R.dimen.spacing_sm);
                else tagLp.topMargin = res.getDimensionPixelSize(R.dimen.spacing_sm);
                statusBox.addView(wrapTagWithFlowBorder(context, timeoutTag, tagLp));
            }
            root.addView(info);
            root.addView(statusBox);

            // 超时件：卡片黄色边框缓慢闪烁（①）
            if (timeout) {
                startBlink(card, trackingNumber);
            }

            // 长按卡片弹出包裹轨迹详情
            card.setOnLongClickListener(v -> {
                try {
                    host.showTrajectory(trackingNumber, QueryFragment.resolveExpressCompanyCode(item));
                } catch (Throwable ignore) {}
                return true;
            });

            // Set tag for differential refresh
            card.setTag(R.id.btn_query, trackingNumber);
            card.setTag(R.id.tag_pkg_status, status == null ? "" : status);
            card.setTag(R.id.tag_pkg_rawpath, rawImgPath);
            container.addView(card);
        } catch (Exception e) {
            host.toast("创建卡片失败: " + e.getMessage());
        }
    }

    // ===== 超时件卡片边框闪烁管理 =====

    /** 启动超时件卡片黄色边框缓慢闪烁（标签不再闪烁，改用流水灯边框） */
    public void startBlink(final CardView card, final String billCode) {
        Runnable old = billCode == null ? null : timeoutBlinkMap.remove(billCode);
        if (old != null) {
            try { mainHandler.removeCallbacks(old); } catch (Exception ignore) {}
        }
        if (card == null) return;
        final boolean[] on = {true};
        Runnable blink = new Runnable() {
            @Override
            public void run() {
                // 卡片已从容器移除（销毁/删除）：停止本任务，避免残留
                if (card.getParent() == null) {
                    stopBlink(billCode);
                    return;
                }
                on[0] = !on[0];
                try {
                    card.setBackgroundResource(on[0] ? R.drawable.bg_pkg_card_timeout : R.drawable.bg_pkg_card);
                } catch (Throwable ignore) {}
                mainHandler.postDelayed(this, 700);
            }
        };
        if (billCode != null) timeoutBlinkMap.put(billCode, blink);
        mainHandler.post(blink);
    }

    /** 停止某个单号的闪烁任务 */
    public void stopBlink(String billCode) {
        if (billCode == null) return;
        Runnable old = timeoutBlinkMap.remove(billCode);
        if (old != null) {
            try { mainHandler.removeCallbacks(old); } catch (Exception ignore) {}
        }
    }

    /** 停止全部闪烁任务 */
    public void stopAllBlink() {
        for (Runnable r : timeoutBlinkMap.values()) {
            try { mainHandler.removeCallbacks(r); } catch (Exception ignore) {}
        }
        timeoutBlinkMap.clear();
    }

    /** 渲染完成后清理：列表中已不存在的包裹停止闪烁，避免任务残留 */
    public void pruneBlink(List<JSONObject> packages) {
        if (timeoutBlinkMap.isEmpty()) return;
        Set<String> alive = new HashSet<>();
        if (packages != null) {
            for (JSONObject p : packages) {
                String bc = firstNonEmpty(p.optString("billCode", ""),
                        p.optString("trackingNumber", ""),
                        p.optString("waybillCode", ""));
                if (bc.length() > 0) alive.add(bc);
            }
        }
        List<String> dead = new ArrayList<>();
        for (String key : timeoutBlinkMap.keySet()) {
            if (!alive.contains(key)) dead.add(key);
        }
        for (String key : dead) stopBlink(key);
    }

    // ===== 流水灯/超时标签 =====

    /** 出库时间外圈"流水灯"标注：黄色流动边框包裹时间 chip */
    private FrameLayout wrapTimeWithFlowBorder(Context ctx, TextView timeChip, LinearLayout.LayoutParams outerLp) {
        FrameLayout frame = new FrameLayout(ctx);
        frame.setLayoutParams(outerLp);
        // 时间 chip 在框内使用"无 margin"的布局参数
        frame.addView(timeChip, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // 后加 FlowBorderView，按 chip 实际尺寸贴合绘制
        FlowBorderView flow = new FlowBorderView(ctx);
        flow.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        frame.addView(flow);
        return frame;
    }

    /** 超时件标签外圈"流水灯"标注：黄色流动边框包裹标签 */
    private FrameLayout wrapTagWithFlowBorder(Context ctx, TextView tag, LinearLayout.LayoutParams outerLp) {
        FrameLayout frame = new FrameLayout(ctx);
        frame.setLayoutParams(outerLp);
        frame.addView(tag, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        FlowBorderView flow = new FlowBorderView(ctx);
        flow.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        frame.addView(flow);
        return frame;
    }

    /** 超时件黄色"超时出库 x天"标签（x=出库时间距离今天的天数，紧挨"已出库"状态文字） */
    private TextView makeTimeoutTag(Context ctx, android.content.res.Resources res, boolean vertical, int days) {
        TextView tag = new TextView(ctx);
        tag.setText("超时出库 " + days + "天");
        tag.setTextColor(ctx.getResources().getColor(R.color.warning, ctx.getTheme()));
        tag.setBackgroundResource(R.drawable.bg_status_timeout);
        tag.setTextSize(14f); // 字号比普通状态标签稍大，突出超时件
        tag.setTypeface(Typeface.DEFAULT_BOLD);
        tag.setPadding(res.getDimensionPixelSize(R.dimen.chip_padding_h),
                res.getDimensionPixelSize(R.dimen.chip_padding_v),
                res.getDimensionPixelSize(R.dimen.chip_padding_h),
                res.getDimensionPixelSize(R.dimen.chip_padding_v));
        return tag;
    }

    // ===== Label/Value Row Helpers =====

    /** 单独调整某行"标签-值"的间距（用于单号行更紧凑，不影响其他行） */
    private static void setLabelGap(LinearLayout row, int rightMarginPx) {
        if (row == null || row.getChildCount() == 0) return;
        View label = row.getChildAt(0);
        ViewGroup.LayoutParams lp = label.getLayoutParams();
        if (lp instanceof LinearLayout.LayoutParams) {
            ((LinearLayout.LayoutParams) lp).rightMargin = rightMarginPx;
            label.setLayoutParams(lp);
        }
    }

    /** 取消某行标签的最小宽度（默认 40dp 会让"单号"后面多出约 10dp 空隙） */
    private static void setLabelMinWidth(LinearLayout row, int minWidthPx) {
        if (row == null || row.getChildCount() == 0) return;
        View label = row.getChildAt(0);
        if (label instanceof TextView) {
            ((TextView) label).setMinWidth(minWidthPx);
        }
    }

    /** 给某行"值"文字右侧预留间距（单号等长文本防最后一位被截断） */
    private static void setValueRightPadding(LinearLayout row, int paddingPx) {
        if (row == null || row.getChildCount() < 2) return;
        View v = row.getChildAt(1);
        if (v instanceof TextView) {
            v.setPadding(0, 0, paddingPx, 0);
        }
    }

    /** 让某行"值"文字可点击：点击直接复制该值（单号/取件码等），收件人报码时更快 */
    private static void makeValueClickableToCopy(final LinearLayout row, final Context ctx,
                                                final String label, final String value) {
        if (row == null || row.getChildCount() < 2) return;
        View vv = row.getChildAt(1);
        if (!(vv instanceof TextView)) return;
        ((TextView) vv).setClickable(true);
        ((TextView) vv).setOnClickListener(v -> {
            if (value == null || value.isEmpty()) return;
            try {
                ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("查件助手", value));
                    Toast.makeText(ctx, "已复制" + label + "：" + value, Toast.LENGTH_SHORT).show();
                    try { LogRecorder.info(ctx, "QUERY", "复制" + label, value); } catch (Exception ignore) {}
                }
            } catch (Throwable ignore) {}
        });
    }

    private static LinearLayout makeLabelValueRow(Context ctx, String label, String value,
                                                   int labelColor, int valueColor, int fallback,
                                                   boolean valueBold, int bottomMargin,
                                                   int labelSizeSp, int valueSizeSp,
                                                   boolean singleLine) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = bottomMargin;
        row.setLayoutParams(rlp);

        TextView lv = new TextView(ctx);
        lv.setText(label);
        lv.setTextColor(labelColor);
        lv.setTextSize(labelSizeSp);
        lv.setTypeface(Typeface.DEFAULT_BOLD);
        lv.setAllCaps(true);
        lv.setLetterSpacing(0.08f);
        lv.setMinWidth(ctx.getResources().getDimensionPixelSize(R.dimen.grid_label_min_width));
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        // 标签与值之间的间隔：6dp→4dp，进一步缩小"单号"等标签与值之间的空隙
        llp.rightMargin = ctx.getResources().getDimensionPixelSize(R.dimen.spacing_xs);
        lv.setLayoutParams(llp);
        row.addView(lv);

        TextView vv = new TextView(ctx);
        vv.setText(value == null || value.isEmpty() ? "\u2014" : value);
        vv.setTextColor(value == null || value.isEmpty() ? fallback : valueColor);
        vv.setTextSize(valueSizeSp);
        if (valueBold) vv.setTypeface(Typeface.DEFAULT_BOLD);
        if (singleLine) {
            vv.setSingleLine(true);
            vv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        } else {
            vv.setSingleLine(false);
        }
        row.addView(vv);
        return row;
    }

    private static void addLabelValueRow(LinearLayout parent, String label, String value,
                                          int labelColor, int valueColor, int fallback,
                                          boolean valueBold, int bottomMargin,
                                          int labelSizeSp, int valueSizeSp,
                                          boolean singleLine) {
        Context ctx = parent.getContext();
        parent.addView(makeLabelValueRow(ctx, label, value, labelColor, valueColor, fallback,
                valueBold, bottomMargin, labelSizeSp, valueSizeSp, singleLine));
    }

    private static TextView makeMetaChip(Context ctx, String text, int border, int textColor) {
        return makeMetaChip(ctx, text, border, textColor, false);
    }

    private static TextView makeMetaChip(Context ctx, String text, int border, int textColor, boolean singleLine) {
        TextView tv = new TextView(ctx);
        tv.setText(text == null ? "" : text);
        tv.setTextColor(textColor);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, ctx.getResources().getDimension(R.dimen.chip_text_size));
        final android.content.res.Resources res = ctx.getResources();
        int chipPadH = res.getDimensionPixelSize(R.dimen.chip_padding_h);
        int chipPadV = res.getDimensionPixelSize(R.dimen.chip_padding_v);
        tv.setBackgroundResource(R.drawable.bg_btn_back);
        tv.setPadding(chipPadH, chipPadV, chipPadH, chipPadV);
        if (singleLine) {
            tv.setSingleLine(true);
            tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tv.setMaxWidth(res.getDimensionPixelSize(R.dimen.chip_max_width));
        }
        return tv;
    }

    private static String firstNonEmpty(String... arr) {
        if (arr == null) return "";
        for (String s : arr) {
            if (s != null && s.length() > 0 && !"null".equalsIgnoreCase(s)) return s;
        }
        return "";
    }

    // ===== 纯工具（时间/文本） =====

    /** 解析时间字符串为毫秒；支持 yyyy-MM-dd HH:mm:ss、yyyy-MM-dd、紧凑数字、纯时间戳等。 */
    static long parseTimeMillis(String raw) {
        if (raw == null) return 0;
        String s = raw.trim();
        if (s.length() == 0) return 0;
        try {
            if (s.matches("\\d{13,}")) return Long.parseLong(s);
            if (s.matches("\\d{10}")) return Long.parseLong(s) * 1000L;
            if (s.matches("\\d{14}")) {
                java.util.Date dt = new java.text.SimpleDateFormat("yyyyMMddHHmmss", Locale.getDefault()).parse(s);
                return dt == null ? 0 : dt.getTime();
            }
            if (s.matches("\\d{8}")) {
                java.util.Date dt = new java.text.SimpleDateFormat("yyyyMMdd", Locale.getDefault()).parse(s);
                return dt == null ? 0 : dt.getTime();
            }
            String norm = s.replace('T', ' ').replace('/', '-');
            int plus = norm.indexOf('+');
            if (plus > 0) norm = norm.substring(0, plus);
            int zIdx = norm.indexOf('Z');
            if (zIdx > 0) norm = norm.substring(0, zIdx);
            norm = norm.trim();
            String[] patterns = {"yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd"};
            for (String p : patterns) {
                try {
                    java.text.SimpleDateFormat f = new java.text.SimpleDateFormat(p, Locale.getDefault());
                    f.setLenient(false);
                    java.util.Date dt = f.parse(norm);
                    if (dt != null) return dt.getTime();
                } catch (Exception ignore) {}
            }
        } catch (Throwable ignore) {}
        return 0;
    }

    /** 计算"出库时间距离今天"的天数（按自然日差，同一天为0天） */
    static int daysSinceOutbound(String outTime) {
        long t = parseTimeMillis(outTime);
        if (t <= 0) return 0;
        try {
            java.util.Calendar cal = java.util.Calendar.getInstance();
            long now = cal.getTimeInMillis();
            cal.setTimeInMillis(t);
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0);
            cal.set(java.util.Calendar.MINUTE, 0);
            cal.set(java.util.Calendar.SECOND, 0);
            cal.set(java.util.Calendar.MILLISECOND, 0);
            long dayOut = cal.getTimeInMillis();
            java.util.Calendar calNow = java.util.Calendar.getInstance();
            calNow.set(java.util.Calendar.HOUR_OF_DAY, 0);
            calNow.set(java.util.Calendar.MINUTE, 0);
            calNow.set(java.util.Calendar.SECOND, 0);
            calNow.set(java.util.Calendar.MILLISECOND, 0);
            long dayNow = calNow.getTimeInMillis();
            long days = (dayNow - dayOut) / (24L * 3600 * 1000L);
            return (int) Math.max(0, days);
        } catch (Throwable ignore) {
            return 0;
        }
    }

    /** 判断文本是否为空/未知（null、未知、无、占位符等），用于收件人等可缺失字段 */
    static boolean isUnknownText(String s) {
        if (s == null) return true;
        String t = s.trim();
        if (t.isEmpty()) return true;
        return "未知".equals(t) || "null".equalsIgnoreCase(t) || "无".equals(t)
                || "-".equals(t) || "--".equals(t) || "—".equals(t) || "暂无".equals(t);
    }

    /** 手机号脱敏前缀压缩：前面多个*只显示4个*+后面数字（如 *******2729 → ****2729） */
    static String normalizeMaskedMobile(String m) {
        if (m == null) return "";
        String t = m.trim();
        int i = 0;
        while (i < t.length() && t.charAt(i) == '*') i++;
        if (i > 0) return "****" + t.substring(i);
        return t;
    }
}
