package com.chajianzhushou.app;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteDatabase;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 全局日志记录器：将各模块的日志写入 SQLite 数据库，供 LogsFragment 读取展示。
 * 支持：
 *   - 全局开关（logs_enabled）
 *   - 按模块独立开关（log_module_filter_xxx）
 *   - 去重：同一模块+级别+标题+摘要 3秒内只写一次
 */
public class LogRecorder {

    private static final String TAG = "LogRecorder";
    private static final String PREFS = "chajianzhushou_prefs";
    private static final String KEY_LOGS_ENABLED = "logs_enabled";
    private static final String KEY_MODULE_FILTER_PREFIX = "log_module_filter_";

    // 内置模块列表（key, 中文名, 颜色）
    public static final String[][] MODULE_LIST = {
        {"APP",           "程序运行",        "#7CB342"},
        {"WINDOW",        "窗口控制",        "#42A5F5"},
        {"SETTINGS",      "设置变更",        "#AB47BC"},
        {"AUTH",          "登录认证",        "#FF7043"},
        {"HTTP_OUT",      "HTTP请求",        "#8D6E63"},
        {"QUERY",         "查件查询",        "#26C6DA"},
        {"IMAGE",         "图片加载",        "#EC407A"},
        {"TIMEOUT_QUERY", "超时件查询",      "#FFA726"},
        {"OUTBOUND",      "出库操作",        "#EF5350"},
        {"AUTO_OUTBOUND", "自动出库调度",    "#D4E157"},
        {"TTS",           "语音播报",        "#29B6F6"},
        {"ASR",           "语音识别",        "#9CCC65"},
        // 手机端特有模块（电脑端没有，但在手机端使用）
        {"Main",          "程序启动",        "#7CB342"},
        {"DirectApi",     "直接API",         "#8D6E63"},
        {"Sync",          "SSE同步",         "#42A5F5"},
        {"Query-Perf",    "查询性能",        "#26C6DA"},
        {"Query-Lazy",    "懒加载",          "#26C6DA"},
    };

    // ----- 与 LogsFragment.LogDBHelper / TABLE logs 保持一致 -----
    private static final String DB_NAME = "app_logs.db";
    private static final String TABLE_NAME = "logs";

    public static final String LEVEL_INFO  = "INFO";
    public static final String LEVEL_WARN  = "WARN";
    public static final String LEVEL_ERROR = "ERROR";
    public static final String LEVEL_FATAL = "FATAL";

    // 去重：同一模块+级别+简短标题+简短摘要 3秒内只写一次
    private static final long DEDUP_WINDOW_MS = 3000;
    private static final ConcurrentHashMap<String, Long> DEDUP_CACHE = new ConcurrentHashMap<>();

    private static volatile LogRecorder sInstance;

    private final Context appContext;
    private final Handler writeHandler;
    private final LogsFragment.LogDBHelper dbHelper;
    private SQLiteDatabase writeDb;

    private LogRecorder(Context context) {
        appContext = context.getApplicationContext();
        HandlerThread ht = new HandlerThread("log-recorder-thread");
        ht.start();
        writeHandler = new Handler(ht.getLooper());
        dbHelper = new LogsFragment.LogDBHelper(appContext);
        try {
            writeDb = dbHelper.getWritableDatabase();
        } catch (Exception e) {
            writeDb = null;
            Log.w(TAG, "打开日志数据库失败: " + e.getMessage());
        }
    }

    public static LogRecorder getInstance(Context context) {
        if (sInstance == null) {
            synchronized (LogRecorder.class) {
                if (sInstance == null) sInstance = new LogRecorder(context);
            }
        }
        return sInstance;
    }

    /**
     * 检查指定模块是否启用了日志输出。
     * APP 模块始终启用；其他模块从 SharedPreferences 读取独立开关。
     */
    public static boolean isModuleEnabled(Context ctx, String module) {
        if (module == null) return true;
        if ("APP".equals(module)) return true;
        if (ctx == null) return true;
        try {
            SharedPreferences prefs = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            return prefs.getBoolean(KEY_MODULE_FILTER_PREFIX + module, true); // 默认开启
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * 保存单个模块开关状态
     */
    public static void setModuleEnabled(Context ctx, String module, boolean enabled) {
        if (ctx == null || module == null || "APP".equals(module)) return;
        try {
            ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_MODULE_FILTER_PREFIX + module, enabled).apply();
        } catch (Exception ignore) {}
    }

    /**
     * 获取所有模块开关状态
     */
    public static java.util.Map<String, Boolean> getAllModuleStates(Context ctx) {
        java.util.Map<String, Boolean> states = new java.util.LinkedHashMap<>();
        if (ctx == null) return states;
        SharedPreferences prefs = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        for (String[] m : MODULE_LIST) {
            String key = m[0];
            states.put(key, "APP".equals(key) ? true : prefs.getBoolean(KEY_MODULE_FILTER_PREFIX + key, true));
        }
        return states;
    }

    // ================== 对外 API ==================

    /** 记录 INFO 级别日志。 */
    public static void info(Context ctx, String module, String title, String summary) {
        record(ctx, LEVEL_INFO, module, title, summary, null, null, false);
    }

    /** 记录 WARN 级别日志。 */
    public static void warn(Context ctx, String module, String title, String summary) {
        record(ctx, LEVEL_WARN, module, title, summary, null, null, false);
    }

    public static void warn(Context ctx, String module, String title, String summary, String cause) {
        record(ctx, LEVEL_WARN, module, title, summary, cause, null, false);
    }

    /** 记录 ERROR 级别日志。 */
    public static void error(Context ctx, String module, String title, String summary) {
        record(ctx, LEVEL_ERROR, module, title, summary, null, null, false);
    }

    public static void error(Context ctx, String module, String title, String summary, Throwable cause) {
        String causeStr = cause == null ? null : (cause.getMessage() == null ? cause.toString() : cause.getMessage());
        String detail = null;
        if (cause != null) {
            StringBuilder sb = new StringBuilder();
            for (StackTraceElement ste : cause.getStackTrace()) {
                sb.append(ste.toString()).append('\n');
                if (sb.length() > 2000) break;
            }
            detail = sb.toString();
        }
        record(ctx, LEVEL_ERROR, module, title, summary, causeStr, detail, true);
    }

    /** 记录 FATAL 级别日志。 */
    public static void fatal(Context ctx, String module, String title, String summary, Throwable cause) {
        String causeStr = cause == null ? null : (cause.getMessage() == null ? cause.toString() : cause.getMessage());
        String detail = null;
        if (cause != null) {
            StringBuilder sb = new StringBuilder();
            for (StackTraceElement ste : cause.getStackTrace()) {
                sb.append(ste.toString()).append('\n');
                if (sb.length() > 4000) break;
            }
            detail = sb.toString();
        }
        record(ctx, LEVEL_FATAL, module, title, summary, causeStr, detail, true);
    }

    // ================== 核心写入 ==================

    private static void record(final Context ctx,
                               final String level,
                               final String module,
                               final String title,
                               final String summary,
                               final String cause,
                               final String detail,
                               final boolean important) {
        // 无传入 Context 时回退到 MainActivity 全局 Context
        Context useCtx = ctx;
        if (useCtx == null) {
            try { useCtx = MainActivity.getAppContext(); } catch (Exception ignore) {}
        }
        // 总开关未开启时不写入（默认开启，与设置页 UI 默认值一致，避免"开关显示开但日志不记录"）
        if (useCtx != null) {
            try {
                SharedPreferences prefs = useCtx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                if (!prefs.getBoolean(KEY_LOGS_ENABLED, true)) return;
            } catch (Exception ignore) {}
        }
        // 模块独立开关过滤（APP 始终允许）
        if (module != null && !"APP".equals(module) && useCtx != null) {
            if (!isModuleEnabled(useCtx, module)) return;
        }
        // 去重：同一模块+级别+标题摘要 在3秒内只写一次（APP和FATAL除外）
        if (module != null && !"APP".equals(module) && !LEVEL_FATAL.equals(level)) {
            String fp = module + "|" + level + "|"
                    + (title != null ? title.substring(0, Math.min(title.length(), 60)) : "")
                    + "|" + (summary != null ? summary.substring(0, Math.min(summary.length(), 60)) : "");
            Long lastAt = DEDUP_CACHE.get(fp);
            long now = System.currentTimeMillis();
            if (lastAt != null && (now - lastAt) < DEDUP_WINDOW_MS) return;
            DEDUP_CACHE.put(fp, now);
        }

        final Context appCtx = (useCtx == null) ? null : useCtx.getApplicationContext();
        final String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        final String date = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());

        // 仅 WARN/ERROR/FATAL 输出到 logcat（INFO 级别的 logcat 输出由业务代码自己的 Log.d 负责，避免重复）
        try {
            if (LEVEL_WARN.equals(level))      Log.w(module == null ? TAG : module, safeJoin(title, summary));
            else if (LEVEL_ERROR.equals(level) || LEVEL_FATAL.equals(level))
                                                Log.e(module == null ? TAG : module, safeJoin(title, summary));
        } catch (Exception ignore) {}

        if (appCtx == null) return;
        LogRecorder inst;
        try {
            inst = LogRecorder.getInstance(appCtx);
        } catch (Exception e) {
            return;
        }
        if (inst == null) return;
        final LogRecorder self = inst;
        self.writeHandler.post(() -> self.doWrite(level, module, title, summary, cause, detail, important, time, date));
    }

    private static String safeJoin(String a, String b) {
        if (a == null || a.length() == 0) return b == null ? "" : b;
        if (b == null || b.length() == 0) return a;
        return a + " | " + b;
    }

    private void doWrite(String level, String module, String title, String summary,
                         String cause, String detail, boolean important,
                         String time, String date) {
        SQLiteDatabase db = writeDb;
        if (db == null) {
            try {
                db = dbHelper.getWritableDatabase();
                writeDb = db;
            } catch (Exception e) {
                return;
            }
        }
        if (db == null) return;
        try {
            ContentValues cv = new ContentValues();
            cv.put("level", level == null ? LEVEL_INFO : level);
            cv.put("module", truncate(module, 32));
            cv.put("title", truncate(title, 100));
            cv.put("summary", truncate(summary, 500));
            cv.put("log_time", time);
            cv.put("log_date", date);
            cv.put("date", date);
            cv.put("detail", truncate(detail, 4000));
            cv.put("cause", truncate(cause, 500));
            cv.put("is_important", important ? 1 : 0);
            db.insert(TABLE_NAME, null, cv);
        } catch (Exception e) {
            try {
                // 表可能不存在，尝试重建
                db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_NAME + " (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "level TEXT, module TEXT, title TEXT, summary TEXT, " +
                        "log_time TEXT, log_date TEXT, date TEXT, " +
                        "detail TEXT, cause TEXT, is_important INTEGER DEFAULT 0)");
            } catch (Exception ignore) {}
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        if (s.length() <= max) return s;
        return s.substring(0, max);
    }
}
