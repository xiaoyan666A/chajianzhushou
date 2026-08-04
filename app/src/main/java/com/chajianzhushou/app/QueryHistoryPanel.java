package com.chajianzhushou.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 查询历史面板：负责"最近查询"记录的存储、渲染与长按删除。
 * 从 QueryFragment 拆出；与宿主的交互通过 Host 接口回抛。
 */
public class QueryHistoryPanel {

    /** 宿主交互接口 */
    public interface Host {
        /** 输入框（回填查询值时使用） */
        EditText input();
        /** 悬浮定位基准根视图 */
        View root();
        /** 切换查询类型（phoneTail/pickupCode/billCode） */
        void setSearchType(String type);
        /** 触发自动查询 */
        void runQuery();
        /** Toast 提示 */
        void toast(String msg);
    }

    private static final String PREFS_NAME = "chajianzhushou_prefs";
    private static final String PREFS_HISTORY = "query_history";
    private static final int MAX_HISTORY = 20;
    private static final int HISTORY_PANEL_MAX_ROWS = 10;

    private final Context context; // 注意：必须是 Activity 上下文（带主题），否则 getColor 不随 AppCompat 强制主题变化
    private final LinearLayout panel;
    private final Host host;
    private View armedHistoryView; // 当前处于"删除待命"态的历史 chip（null=无）

    public QueryHistoryPanel(Context context, LinearLayout panel, Host host) {
        this.context = context;
        this.panel = panel;
        this.host = host;
    }

    private SharedPreferences prefs() {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public List<JSONObject> load() {
        List<JSONObject> list = new ArrayList<>();
        try {
            String raw = prefs().getString(PREFS_HISTORY, "");
            if (raw != null && raw.length() > 0) {
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o != null) list.add(o);
                }
            }
        } catch (Throwable ignore) {}
        return list;
    }

    private void save(List<JSONObject> list) {
        try {
            JSONArray arr = new JSONArray();
            for (JSONObject o : list) arr.put(o);
            prefs().edit().putString(PREFS_HISTORY, arr.toString()).apply();
        } catch (Throwable ignore) {}
    }

    /** 记录一条查询历史：相同"值+类型"去重并移到最前，超出上限裁剪 */
    public void record(String value, String type) {
        if (value == null || value.isEmpty()) return;
        try {
            List<JSONObject> list = load();
            list.removeIf(o -> value.equals(o.optString("v", "")) && type.equals(o.optString("t", "")));
            JSONObject o = new JSONObject();
            o.put("v", value);
            o.put("t", type);
            list.add(0, o);
            while (list.size() > MAX_HISTORY) list.remove(list.size() - 1);
            save(list);
        } catch (Throwable ignore) {}
    }

    /** 从历史记录中删除"值+类型"匹配的一条；返回是否删除成功 */
    public boolean delete(String value, String type) {
        try {
            List<JSONObject> list = load();
            boolean removed = list.removeIf(o -> value.equals(o.optString("v", "")) && type.equals(o.optString("t", "")));
            if (!removed) return false;
            save(list);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 渲染最近查询面板：点击记录自动回填输入框、选中对应类型并自动查询 */
    public void render() {
        if (panel == null) return;
        panel.removeAllViews();
        armedHistoryView = null; // 重渲染后所有 chip 恢复正常态（待命态不跨渲染保留）
        List<JSONObject> list = load();
        if (list.isEmpty()) {
            panel.setVisibility(View.GONE);
            return;
        }
        // 悬浮定位：面板覆盖在搜索框正下方（不挤压下方控件），与搜索框左右对齐
        try {
            View root = host.root();
            EditText input = host.input();
            if (root != null && input != null) {
                int[] rootLoc = new int[2];
                int[] boxLoc = new int[2];
                root.getLocationOnScreen(rootLoc);
                input.getLocationOnScreen(boxLoc);
                int top = boxLoc[1] - rootLoc[1] + input.getHeight();
                int pad = context.getResources().getDimensionPixelSize(R.dimen.pad_page_h);
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) panel.getLayoutParams();
                lp.gravity = Gravity.TOP;
                lp.topMargin = top;
                lp.leftMargin = pad;
                lp.rightMargin = pad;
                lp.width = FrameLayout.LayoutParams.MATCH_PARENT;
                panel.setLayoutParams(lp);
            }
        } catch (Throwable ignore) {}
        panel.setVisibility(View.VISIBLE);
        try {
            android.content.res.Resources res = context.getResources();
            int ink2 = context.getResources().getColor(R.color.ink2, context.getTheme());
            int muted = context.getResources().getColor(R.color.muted, context.getTheme());
            int padH = res.getDimensionPixelSize(R.dimen.spacing_lg);
            int padV = res.getDimensionPixelSize(R.dimen.spacing_lg);

            // 面板标题
            TextView header = new TextView(context);
            header.setText("最近查询");
            header.setTextColor(muted);
            header.setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.txt_sm));
            header.setTypeface(Typeface.DEFAULT_BOLD);
            header.setPadding(padH, padV, padH, padV);
            panel.addView(header);

            // 横向滚动容器：每条记录一个圆角轮廓 chip
            HorizontalScrollView hsv = new HorizontalScrollView(context);
            hsv.setHorizontalScrollBarEnabled(false);
            LinearLayout chipRow = new LinearLayout(context);
            chipRow.setOrientation(LinearLayout.HORIZONTAL);
            chipRow.setGravity(Gravity.CENTER_VERTICAL);
            hsv.addView(chipRow);
            panel.addView(hsv, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            int chipPadH = res.getDimensionPixelSize(R.dimen.spacing_lg);
            int chipPadV = res.getDimensionPixelSize(R.dimen.spacing_sm);
            int margin = res.getDimensionPixelSize(R.dimen.spacing_md);

            int shown = 0;
            for (JSONObject o : list) {
                if (shown >= HISTORY_PANEL_MAX_ROWS) break;
                final String v = o.optString("v", "");
                final String t = o.optString("t", "");
                if (v.isEmpty()) continue;
                String prefix = "phoneTail".equals(t) ? "手机尾号 " : ("pickupCode".equals(t) ? "取件码 " : "运单号 ");

                // 每张 chip 升级为横向容器：文字 + 右侧"×"删除按钮（默认隐藏，长按后显示）
                LinearLayout container = new LinearLayout(context);
                container.setOrientation(LinearLayout.HORIZONTAL);
                container.setGravity(Gravity.CENTER_VERTICAL);
                container.setClickable(true);
                container.setLongClickable(true);
                container.setPadding(chipPadH, chipPadV, chipPadH, chipPadV);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.rightMargin = margin;
                container.setLayoutParams(lp);
                applyArmedStyle(container, false);

                TextView chipText = new TextView(context);
                chipText.setText(prefix + v);
                chipText.setTextColor(ink2);
                chipText.setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.txt_sm));
                chipText.setSingleLine(true);
                chipText.setEllipsize(android.text.TextUtils.TruncateAt.END);
                chipText.setMaxWidth(res.getDimensionPixelSize(R.dimen.chip_max_width));
                container.addView(chipText);

                // "×"删除按钮：红色圆形，长按进入待命态后显示
                TextView deleteBtn = new TextView(context);
                deleteBtn.setText("×");
                deleteBtn.setTextColor(0xFFFFFFFF);
                deleteBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                deleteBtn.setGravity(Gravity.CENTER);
                deleteBtn.setVisibility(View.GONE);
                android.graphics.drawable.GradientDrawable circle = new android.graphics.drawable.GradientDrawable();
                circle.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                circle.setColor(context.getResources().getColor(R.color.danger, context.getTheme()));
                deleteBtn.setBackground(circle);
                int delSize = (int) (18 * context.getResources().getDisplayMetrics().density + 0.5f);
                LinearLayout.LayoutParams delLp = new LinearLayout.LayoutParams(delSize, delSize);
                delLp.leftMargin = (int) (6 * context.getResources().getDisplayMetrics().density + 0.5f);
                delLp.gravity = Gravity.CENTER_VERTICAL;
                deleteBtn.setLayoutParams(delLp);
                container.addView(deleteBtn);

                // 点击：待命态下只取消待命、不触发查询；正常态回填+查询
                container.setOnClickListener(vv -> {
                    try {
                        if (armedHistoryView != null) {
                            disarm();
                            return;
                        }
                        EditText input = host.input();
                        if (input != null) input.setText(v);
                        host.setSearchType(t);
                        hide();
                        host.runQuery();
                    } catch (Throwable ignore) {}
                });
                // 长按：进入删除待命态（仅该 chip 显示"×"、边框变红）
                container.setOnLongClickListener(vv -> {
                    try {
                        disarm();
                        armedHistoryView = container;
                        applyArmedStyle(container, true);
                    } catch (Throwable ignore) {}
                    return true;
                });
                // 点"×"：删除该条记录，Toast 提示，面板保持展开（删空自动隐藏）
                deleteBtn.setOnClickListener(vv -> {
                    try {
                        if (delete(v, t)) {
                            host.toast("已删除");
                        } else {
                            host.toast("删除失败");
                        }
                        render();
                    } catch (Throwable ignore) {}
                });
                chipRow.addView(container);
                shown++;
            }
        } catch (Throwable ignore) {}
    }

    /** 收起查询历史面板 */
    public void hide() {
        armedHistoryView = null; // 面板收起时清除删除待命态
        if (panel != null) panel.setVisibility(View.GONE);
    }

    /** 设置/恢复历史 chip 的样式：armed=true 红色边框+"×"显示；false 恢复默认样式 */
    private void applyArmedStyle(View container, boolean armed) {
        if (container == null) return;
        try {
            android.content.res.Resources res = context.getResources();
            int strokePx = Math.max(1, res.getDimensionPixelSize(R.dimen.divider_height));
            int corner = res.getDimensionPixelSize(R.dimen.radius_md);
            int border = context.getResources().getColor(R.color.hair2, context.getTheme());
            int danger = context.getResources().getColor(R.color.danger, context.getTheme());
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
            bg.setCornerRadius(corner);
            bg.setColor(0x00000000);
            bg.setStroke(strokePx, armed ? danger : border);
            container.setBackground(bg);
            if (container instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) container;
                if (vg.getChildCount() > 1) {
                    vg.getChildAt(1).setVisibility(armed ? View.VISIBLE : View.GONE);
                }
            }
        } catch (Throwable ignore) {}
    }

    /** 取消历史 chip 的删除待命态 */
    private void disarm() {
        if (armedHistoryView != null) {
            applyArmedStyle(armedHistoryView, false);
            armedHistoryView = null;
        }
    }
}
