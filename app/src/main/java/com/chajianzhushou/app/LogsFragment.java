package com.chajianzhushou.app;

import android.content.Context;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class LogsFragment extends Fragment {

    private static final String DB_NAME = "app_logs.db";
    private static final String TABLE_NAME = "logs";
    // 分页大小：每页加载 200 条，全部日志均可通过"加载更多"查看
    private static final int PAGE_SIZE = 200;

    private TextView tvLogsCount;
    private Button btnBack;
    private Button btnDelete;
    private Button btnLoadMore;
    private Spinner spinnerDate;
    private Spinner spinnerLevel;
    private Spinner spinnerModule;
    private LinearLayout logsListContainer;
    private LinearLayout logsEmpty;
    private ProgressBar progressBar;

    private SQLiteDatabase db;
    private Handler mainHandler;
    private boolean isViewReady = false;

    private List<String> dateOptions = new ArrayList<>();
    private List<String> moduleOptions = new ArrayList<>();
    private String selectedDate = "";
    private String selectedLevel = "ALL";
    private String selectedModule = "ALL";
    // 当前筛选条件下已加载的日志与总条数
    private final List<LogItem> allLoaded = new ArrayList<>();
    private int totalCount = 0;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_logs, container, false);

        tvLogsCount = view.findViewById(R.id.tv_logs_count);
        btnBack = view.findViewById(R.id.btn_back_logs);
        btnDelete = view.findViewById(R.id.btn_logs_delete);
        btnLoadMore = view.findViewById(R.id.btn_logs_load_more);
        spinnerDate = view.findViewById(R.id.spinner_logs_date);
        spinnerLevel = view.findViewById(R.id.spinner_logs_level);
        spinnerModule = view.findViewById(R.id.spinner_logs_module);
        logsListContainer = view.findViewById(R.id.logs_list_container);
        logsEmpty = view.findViewById(R.id.logs_empty);
        progressBar = view.findViewById(R.id.logs_progress_bar);

        mainHandler = new Handler(Looper.getMainLooper());
        isViewReady = true;

        // Open DB
        try {
            LogDBHelper helper = new LogDBHelper(requireContext());
            db = helper.getWritableDatabase();
        } catch (Exception e) {
            db = null;
        }

        // Back button
        btnBack.setOnClickListener(v -> {
            try {
                if (getActivity() instanceof MainActivity) {
                    ((MainActivity) getActivity()).switchPage("settings");
                }
            } catch (Exception ignore) {}
        });

        // Level spinner
        ArrayAdapter<CharSequence> levelAdapter = ArrayAdapter.createFromResource(
                requireContext(), R.array.logs_level_options, R.layout.spinner_item);
        levelAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        spinnerLevel.setAdapter(levelAdapter);
        spinnerLevel.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View v, int pos, long id) {
                String[] levels = {"ALL", "INFO", "WARN", "ERROR", "FATAL"};
                selectedLevel = pos < levels.length ? levels[pos] : "ALL";
                loadLogs();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        // Module spinner (类别筛选)
        ArrayAdapter<CharSequence> defaultModuleAdapter = ArrayAdapter.createFromResource(
                requireContext(), R.array.logs_module_default, R.layout.spinner_item);
        defaultModuleAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        spinnerModule.setAdapter(defaultModuleAdapter);
        spinnerModule.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View v, int pos, long id) {
                if (pos == 0) {
                    selectedModule = "ALL";
                } else if (pos - 1 < moduleOptions.size()) {
                    selectedModule = moduleOptions.get(pos - 1);
                } else {
                    selectedModule = "ALL";
                }
                loadLogs();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        // 加载更多按钮：点击加载下一批日志（分页）
        btnLoadMore.setOnClickListener(v -> loadMoreLogs());

        // Delete button
        btnDelete.setOnClickListener(v -> {
            if (db != null && selectedDate.length() > 0) {
                try {
                    db.delete(TABLE_NAME, "date=? OR log_date=?", new String[]{selectedDate, selectedDate});
                    safeToast("已删除 " + selectedDate + " 的日志");
                    loadDates();
                    loadModules();
                    loadLogs();
                } catch (Exception e) {
                    safeToast("删除失败: " + e.getMessage());
                }
            }
        });

        loadDates();
        loadModules();
        loadLogs();

        return view;
    }

    @Override
    public void onDestroyView() {
        isViewReady = false;
        if (db != null) {
            try { db.close(); } catch (Exception ignore) {}
            db = null;
        }
        super.onDestroyView();
    }

    private void safeToast(String msg) {
        if (!isAdded() || !isViewReady) return;
        Context ctx = getContext();
        if (ctx == null) return;
        try { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show(); } catch (Exception ignore) {}
    }

    private void loadDates() {
        if (!isViewReady || db == null) return;
        dateOptions.clear();
        dateOptions.add("今天");
        try {
            Cursor c = db.rawQuery("SELECT DISTINCT COALESCE(date, log_date, '') as d FROM " + TABLE_NAME + " ORDER BY d DESC LIMIT 30", null);
            while (c.moveToNext()) {
                String d = c.getString(0);
                if (d != null && d.length() > 0 && !dateOptions.contains(d)) {
                    dateOptions.add(d);
                }
            }
            c.close();
        } catch (Exception e) {
            // table might not exist yet
        }

        if (spinnerDate != null) {
            ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(), R.layout.spinner_item, dateOptions);
            adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
            spinnerDate.setAdapter(adapter);
            spinnerDate.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(AdapterView<?> parent, View v, int pos, long id) {
                    selectedDate = pos == 0 ? getToday() : dateOptions.get(pos);
                    loadLogs();
                }
                @Override public void onNothingSelected(AdapterView<?> parent) {}
            });
        }
    }

    private void loadModules() {
        if (!isViewReady || db == null) return;
        moduleOptions.clear();
        try {
            Cursor c = db.rawQuery("SELECT DISTINCT COALESCE(module, '') as m FROM " + TABLE_NAME + " ORDER BY m ASC", null);
            while (c.moveToNext()) {
                String m = c.getString(0);
                if (m != null && m.length() > 0 && !moduleOptions.contains(m)) {
                    moduleOptions.add(m);
                }
            }
            c.close();
        } catch (Exception e) {
            // table might not exist yet
        }

        if (spinnerModule != null) {
            List<String> displayOptions = new ArrayList<>();
            displayOptions.add("所有类别");
            displayOptions.addAll(moduleOptions);
            ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(), R.layout.spinner_item, displayOptions);
            adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
            spinnerModule.setAdapter(adapter);
        }
    }

    private String getToday() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
    }

    private void loadLogs() {
        if (!isViewReady || logsListContainer == null || logsEmpty == null) return;
        if (db == null) {
            showEmpty(true);
            updateCount(0);
            updateLoadMore(false);
            return;
        }

        allLoaded.clear();
        totalCount = 0;
        showLoading(true);
        new Thread(() -> {
            final List<LogItem> page = new ArrayList<>();
            final int[] total = {0};
            try {
                List<String> conditions = new ArrayList<>();
                List<String> args = new ArrayList<>();
                String[] argArr = buildWhere(conditions, args);
                String whereClause = conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);

                // 先统计当前筛选条件下的总条数（用于计数徽标与"加载更多"显隐）
                Cursor cc = db.rawQuery("SELECT COUNT(*) FROM " + TABLE_NAME + whereClause, argArr);
                if (cc.moveToFirst()) total[0] = cc.getInt(0);
                cc.close();

                Cursor c = db.rawQuery(
                        "SELECT level, module, title, summary, log_time, log_date, detail, cause, is_important FROM " + TABLE_NAME
                                + whereClause + " ORDER BY log_time DESC LIMIT " + PAGE_SIZE, argArr);
                while (c.moveToNext()) page.add(readLogItem(c));
                c.close();
            } catch (Exception e) {
                // table may not exist
            }

            mainHandler.post(() -> {
                showLoading(false);
                totalCount = total[0];
                allLoaded.addAll(page);
                renderLogs(allLoaded);
                updateCount(totalCount);
                updateLoadMore(allLoaded.size() < totalCount);
            });
        }).start();
    }

    /** 加载下一批日志并追加到列表末尾。 */
    private void loadMoreLogs() {
        if (db == null || logsListContainer == null || btnLoadMore == null) return;
        final int offset = allLoaded.size();
        showLoading(true);
        new Thread(() -> {
            final List<LogItem> page = new ArrayList<>();
            try {
                List<String> conditions = new ArrayList<>();
                List<String> args = new ArrayList<>();
                String[] argArr = buildWhere(conditions, args);
                String whereClause = conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
                Cursor c = db.rawQuery(
                        "SELECT level, module, title, summary, log_time, log_date, detail, cause, is_important FROM " + TABLE_NAME
                                + whereClause + " ORDER BY log_time DESC LIMIT " + PAGE_SIZE + " OFFSET " + offset, argArr);
                while (c.moveToNext()) page.add(readLogItem(c));
                c.close();
            } catch (Exception e) {
                // table may not exist
            }

            mainHandler.post(() -> {
                showLoading(false);
                if (page.isEmpty()) {
                    updateLoadMore(false);
                    return;
                }
                allLoaded.addAll(page);
                for (LogItem item : page) addLogCard(item);
                updateCount(totalCount);
                updateLoadMore(allLoaded.size() < totalCount);
            });
        }).start();
    }

    /** 拼接当前筛选（日期/级别/类别）的 WHERE 条件与参数。 */
    private String[] buildWhere(List<String> conditions, List<String> args) {
        String today = getToday();
        String dateFilter = selectedDate.isEmpty() || "今天".equals(selectedDate) ? today : selectedDate;
        conditions.add("(date=? OR log_date=?)");
        args.add(dateFilter);
        args.add(dateFilter);

        if (!"ALL".equals(selectedLevel)) {
            conditions.add("level=?");
            args.add(selectedLevel);
        }

        if (!"ALL".equals(selectedModule)) {
            conditions.add("module=?");
            args.add(selectedModule);
        }
        return args.toArray(new String[0]);
    }

    private LogItem readLogItem(Cursor c) {
        LogItem item = new LogItem();
        item.level = c.getString(0);
        item.module = c.getString(1);
        item.title = c.getString(2);
        item.summary = c.getString(3);
        item.logTime = c.getString(4);
        item.logDate = c.getString(5);
        item.detail = c.getString(6);
        item.cause = c.getString(7);
        item.isImportant = c.getInt(8) == 1;
        return item;
    }

    private void showLoading(boolean show) {
        if (progressBar != null) {
            progressBar.setVisibility(show ? View.VISIBLE : View.GONE);
        }
    }

    private void showEmpty(boolean show) {
        if (logsEmpty != null) logsEmpty.setVisibility(show ? View.VISIBLE : View.GONE);
        if (logsListContainer != null) logsListContainer.setVisibility(show ? View.GONE : View.VISIBLE);
    }

    private void updateCount(int count) {
        if (tvLogsCount != null) {
            tvLogsCount.setText("共 " + count + " 条");
        }
    }

    private void updateLoadMore(boolean show) {
        if (btnLoadMore != null) {
            btnLoadMore.setVisibility(show ? View.VISIBLE : View.GONE);
        }
    }

    private void renderLogs(List<LogItem> items) {
        if (!isViewReady || logsListContainer == null || logsEmpty == null) return;

        logsListContainer.removeAllViews();

        if (items.isEmpty()) {
            showEmpty(true);
            return;
        }

        showEmpty(false);

        for (LogItem item : items) {
            addLogCard(item);
        }
    }

    private void addLogCard(LogItem item) {
        if (!isViewReady || logsListContainer == null) return;
        Context ctx = getContext();
        if (ctx == null) return;

        try {
            LinearLayout card = new LinearLayout(ctx);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackgroundResource(R.drawable.bg_settings_card);
            card.setPadding(dp(14), dp(12), dp(14), dp(12));

            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            cardParams.bottomMargin = dp(8);
            card.setLayoutParams(cardParams);

            if (item.isImportant) {
                card.setBackgroundResource(R.drawable.bg_btn_warning);
            }

            // Top row: level dot + time + module + level tag
            LinearLayout topRow = new LinearLayout(ctx);
            topRow.setOrientation(LinearLayout.HORIZONTAL);
            topRow.setGravity(android.view.Gravity.CENTER_VERTICAL);

            // Level dot
            View dot = new View(ctx);
            dot.setLayoutParams(new LinearLayout.LayoutParams(dp(8), dp(8)));
            dot.setBackgroundResource(getLevelDotColor(item.level));
            LinearLayout.LayoutParams dotParams = (LinearLayout.LayoutParams) dot.getLayoutParams();
            dotParams.rightMargin = dp(8);
            topRow.addView(dot);

            // Time
            TextView tvTime = new TextView(ctx);
            tvTime.setText(item.logTime != null ? item.logTime : "");
            tvTime.setTextColor(ctx.getResources().getColor(R.color.muted, ctx.getTheme()));
            tvTime.setTextSize(14);
            LinearLayout.LayoutParams timeParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            timeParams.rightMargin = dp(8);
            tvTime.setLayoutParams(timeParams);
            topRow.addView(tvTime);

            // Module badge
            if (item.module != null && item.module.length() > 0) {
                TextView tvModule = new TextView(ctx);
                tvModule.setText(item.module);
                tvModule.setTextColor(0xFFFFFFFF);
                tvModule.setTextSize(12);
                tvModule.setBackgroundResource(R.drawable.bg_header_countdown);
                tvModule.setPadding(dp(6), dp(2), dp(6), dp(2));
                LinearLayout.LayoutParams modParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                modParams.rightMargin = dp(6);
                tvModule.setLayoutParams(modParams);
                topRow.addView(tvModule);
            }

            // Level tag
            TextView tvLevel = new TextView(ctx);
            tvLevel.setText(item.level != null ? item.level.toUpperCase() : "INFO");
            tvLevel.setTextSize(12);
            tvLevel.setPadding(dp(6), dp(2), dp(6), dp(2));
            int bgColor = getLevelBgColor(item.level);
            int fgColor = getLevelTextColor(item.level);
            tvLevel.setTextColor(fgColor);
            // 级别标签圆角背景：与模块徽标一致，浅色/深色主题下均清晰可辨
            android.graphics.drawable.GradientDrawable levelBg = new android.graphics.drawable.GradientDrawable();
            levelBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
            levelBg.setCornerRadius(dp(6));
            levelBg.setColor(bgColor);
            tvLevel.setBackground(levelBg);

            topRow.addView(tvLevel);

            card.addView(topRow);

            // Title
            if (item.title != null && item.title.length() > 0) {
                TextView tvTitle = new TextView(ctx);
                tvTitle.setText(item.title);
                tvTitle.setTextColor(ctx.getResources().getColor(R.color.ink, ctx.getTheme()));
                tvTitle.setTextSize(16);
                tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
                LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                titleParams.topMargin = dp(6);
                tvTitle.setLayoutParams(titleParams);
                card.addView(tvTitle);
            }

            // Summary
            if (item.summary != null && item.summary.length() > 0) {
                TextView tvSummary = new TextView(ctx);
                tvSummary.setText(item.summary);
                tvSummary.setTextColor(ctx.getResources().getColor(R.color.muted, ctx.getTheme()));
                tvSummary.setTextSize(14);
                tvSummary.setMaxLines(3);
                LinearLayout.LayoutParams sumParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                sumParams.topMargin = dp(4);
                tvSummary.setLayoutParams(sumParams);
                card.addView(tvSummary);
            }

            // Cause box (if present)
            if (item.cause != null && item.cause.length() > 0) {
                LinearLayout causeBox = new LinearLayout(ctx);
                causeBox.setOrientation(LinearLayout.VERTICAL);
                causeBox.setBackgroundResource(R.drawable.bg_btn_back);
                causeBox.setPadding(dp(10), dp(8), dp(10), dp(8));
                LinearLayout.LayoutParams causeParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                causeParams.topMargin = dp(8);
                causeBox.setLayoutParams(causeParams);

                TextView tvCauseLabel = new TextView(ctx);
                tvCauseLabel.setText("原因");
                tvCauseLabel.setTextColor(ctx.getResources().getColor(R.color.muted, ctx.getTheme()));
                tvCauseLabel.setTextSize(13);
                causeBox.addView(tvCauseLabel);

                TextView tvCause = new TextView(ctx);
                tvCause.setText(item.cause);
                tvCause.setTextColor(ctx.getResources().getColor(R.color.warning, ctx.getTheme()));
                tvCause.setTextSize(14);
                LinearLayout.LayoutParams causeTextParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                causeTextParams.topMargin = dp(4);
                tvCause.setLayoutParams(causeTextParams);
                causeBox.addView(tvCause);

                card.addView(causeBox);
            }

            // Detail（默认收起；长按卡片展开/收起详情）
            if (item.detail != null && item.detail.length() > 0) {
                TextView tvDetail = new TextView(ctx);
                tvDetail.setText(item.detail);
                tvDetail.setTextColor(ctx.getResources().getColor(R.color.ink2, ctx.getTheme()));
                tvDetail.setTextSize(13);
                tvDetail.setMaxLines(2);
                tvDetail.setVisibility(View.GONE);
                LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                detailParams.topMargin = dp(8);
                tvDetail.setLayoutParams(detailParams);
                card.addView(tvDetail);

                card.setOnLongClickListener(v -> {
                    if (tvDetail.getVisibility() == View.GONE) {
                        tvDetail.setVisibility(View.VISIBLE);
                        tvDetail.setMaxLines(Integer.MAX_VALUE);
                    } else {
                        tvDetail.setVisibility(View.GONE);
                        tvDetail.setMaxLines(2);
                    }
                    return true;
                });
            }

            // 点击卡片：复制本条日志内容到剪贴板
            card.setOnClickListener(v -> copyLogItem(item));

            logsListContainer.addView(card);
        } catch (Exception e) {
            // skip
        }
    }

    /** 点击日志卡片：把本条日志完整内容复制到剪贴板 */
    private void copyLogItem(LogItem item) {
        if (item == null) return;
        try {
            StringBuilder sb = new StringBuilder();
            if (item.logDate != null && item.logDate.length() > 0) sb.append("日期：").append(item.logDate).append('\n');
            if (item.logTime != null && item.logTime.length() > 0) sb.append("时间：").append(item.logTime).append('\n');
            if (item.module != null && item.module.length() > 0) sb.append("模块：").append(item.module).append('\n');
            if (item.level != null && item.level.length() > 0) sb.append("级别：").append(item.level).append('\n');
            if (item.title != null && item.title.length() > 0) sb.append("标题：").append(item.title).append('\n');
            if (item.summary != null && item.summary.length() > 0) sb.append("内容：").append(item.summary).append('\n');
            if (item.cause != null && item.cause.length() > 0) sb.append("原因：").append(item.cause).append('\n');
            if (item.detail != null && item.detail.length() > 0) sb.append("详情：").append(item.detail);
            String text = sb.toString().trim();
            if (text.isEmpty()) return;

            ClipboardManager cm = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("查件助手日志", text));
                safeToast("已复制日志（" + (item.module == null ? "" : item.module)
                        + "：" + (item.title == null ? "" : item.title) + "）");
                try {
                    LogRecorder.info(requireContext(), "LOGS", "复制日志",
                            (item.module == null ? "" : item.module) + " | " + (item.title == null ? "" : item.title));
                } catch (Exception ignore) {}
            }
        } catch (Exception e) {
            safeToast("复制失败: " + e.getMessage());
        }
    }

    private int getLevelDotColor(String level) {
        if (level == null) return R.drawable.bg_status_pending;
        switch (level.toUpperCase()) {
            case "WARN": return R.drawable.bg_float_warning;
            case "ERROR": case "FATAL": return R.drawable.bg_status_delivered;
            default: return R.drawable.bg_status_pending;
        }
    }

    private int getLevelBgColor(String level) {
        if (level == null) return 0x2E42A5F5;
        switch (level.toUpperCase()) {
            case "WARN": return 0x2EFFA726;
            case "ERROR": return 0x2EEF5350;
            case "FATAL": return 0x59B71C1C;
            default: return 0x2E42A5F5;
        }
    }

    private int getLevelTextColor(String level) {
        if (level == null) return 0xFF64B5F6;
        switch (level.toUpperCase()) {
            case "WARN": return 0xFFFFB74D;
            case "ERROR": return 0xFFEF5350;
            case "FATAL": return 0xFFEF5350;
            default: return 0xFF64B5F6;
        }
    }

    private int dp(int px) {
        return (int) (px * (requireContext().getResources().getDisplayMetrics().density));
    }

    // ===== Log Item =====
    private static class LogItem {
        String level;
        String module;
        String title;
        String summary;
        String logTime;
        String logDate;
        String detail;
        String cause;
        boolean isImportant;
    }

    // ===== SQLite Helper =====
    public static class LogDBHelper extends SQLiteOpenHelper {
        public LogDBHelper(Context context) {
            super(context, DB_NAME, null, 1);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_NAME + " (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "level TEXT, " +
                    "module TEXT, " +
                    "title TEXT, " +
                    "summary TEXT, " +
                    "log_time TEXT, " +
                    "log_date TEXT, " +
                    "date TEXT, " +
                    "detail TEXT, " +
                    "cause TEXT, " +
                    "is_important INTEGER DEFAULT 0" +
                    ")");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            db.execSQL("DROP TABLE IF EXISTS " + TABLE_NAME);
            onCreate(db);
        }
    }
}
