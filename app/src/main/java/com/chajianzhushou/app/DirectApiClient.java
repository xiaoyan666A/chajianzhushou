package com.chajianzhushou.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Dispatcher;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 直接调用 ZTO API 的客户端（不依赖 PC 服务器）。
 * 当"服务器连接"开关关闭时使用本客户端独立查询。
 */
public class DirectApiClient {
    private static final String TAG = "DirectApi";
    private static final String DEPOT_CODE = "TUXI39300384927";
    // 超时件出库（按抓包文件将超时件出库.har）：固定坐标与 remark
    private static final String TIMEOUT_OUTBOUND_LATION = "116.236085,39.084864";
    private static final String TIMEOUT_OUTBOUND_REMARK = "超时出库";
    // 超时件出库网关（抓包文件中的地址）
    private static final String TIMEOUT_OUTBOUND_URL = "https://ztwjgateway.zto.com/gateway.do/";

    // 登录请求体（与 server.js LOGIN_POST_DATA 一致）
    private static final String LOGIN_BODY = "data=%7B%0A%20%20%22platformName%22%20%3A%20%22app%22%2C%0A%20%20%22deviceId%22%20%3A%20%221750F6BE-5052-4FE6-9579-E8AF49522BA4%22%2C%0A%20%20%22authorization%22%20%3A%20%22tKow9KH19L%2BtE%2BKQ4yceDyYQ2LkAOvdJpmJIGgn8sIk9AMFQb9pF1DltaHCrMKM7mC58e1owKwQ%5C%2FF7qpl%2B7U7Ubqv9ZrFanwaCsnEZi2V1h7rqgl2qSlFUebLNlpgZRaS%5C%2Fkb6LqtpoRVGPuVVCYk7%5C%2FsCOloAD6vnMTW9KHOuQ5w%3D%22%2C%0A%20%20%22verifyId%22%20%3A%20%22%22%2C%0A%20%20%22deviceName%22%20%3A%20%22iPhone%22%0A%7D";

    private final OkHttpClient client;
    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private String accessToken;
    private String userId;
    private long tokenExpiresAt;

    public DirectApiClient() {
        this(MainActivity.getAppContext());
    }

    public DirectApiClient(Context context) {
        Context appCtx = context == null ? MainActivity.getAppContext() : context.getApplicationContext();
        this.appContext = appCtx;
        Dispatcher dispatcher = new Dispatcher();
        dispatcher.setMaxRequests(128);
        dispatcher.setMaxRequestsPerHost(30);
        this.client = new OkHttpClient.Builder()
                .dispatcher(dispatcher)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build();
    }

    // ===== Login =====

    private synchronized JSONObject ensureLogin() throws Exception {
        long now = System.currentTimeMillis();
        if (accessToken != null && tokenExpiresAt > now) {
            JSONObject result = new JSONObject();
            result.put("accessToken", accessToken);
            result.put("userId", userId);
            return result;
        }
        return doLogin();
    }

    private JSONObject doLogin() throws Exception {
        Request request = new Request.Builder()
                .url("https://kdcs-wx-lt.zt-express.com/gateway.do/")
                .header("X-Zop-Name", "tuxi.spm.account.accountLoginByPwd")
                .header("X-App-Version", "4.51.4")
                .header("X-Ca-Version", "1")
                .header("User-Agent", "wanjiaExpress/4.51.4 (iPhone; iOS 26.6; Scale/3.00)")
                .post(RequestBody.create(LOGIN_BODY, MediaType.parse("application/x-www-form-urlencoded")))
                .build();

        try (Response resp = client.newCall(request).execute()) {
            if (resp.body() == null) {
                Exception e = new Exception("登录响应为空");
                Log.w(TAG, e.getMessage());
                try { LogRecorder.error(appContext, "DirectApi", "登录失败", e.getMessage()); } catch (Exception ignore) {}
                throw e;
            }
            JSONObject body = new JSONObject(resp.body().string());
            if (!body.optBoolean("status", false) || body.isNull("result")) {
                String msg = body.optString("message", "未知错误");
                Log.w(TAG, "登录失败: " + msg);
                try { LogRecorder.error(appContext, "DirectApi", "登录失败", msg); } catch (Exception ignore) {}
                throw new Exception("登录失败: " + msg);
            }
            JSONObject result = body.getJSONObject("result");
            accessToken = result.optString("accessToken", "");
            userId = result.optString("userId", "");
            tokenExpiresAt = System.currentTimeMillis() + 24 * 60 * 60 * 1000L;
            Log.d(TAG, "登录成功, userId=" + userId);
            try { LogRecorder.info(appContext, "DirectApi", "登录成功", "userId=" + userId); } catch (Exception ignore) {}
            return result;
        }
    }

    // ===== Get Image URL =====

    private String getEncryptFileUrl(String billCode, String fileName, JSONObject auth) throws Exception {
        if (billCode == null || fileName == null || billCode.length() == 0 || fileName.length() == 0) return "";
        Request request = buildEncryptFileUrlRequest(billCode, fileName, auth);
        try (Response resp = client.newCall(request).execute()) {
            if (resp.body() != null) {
                JSONObject b = new JSONObject(resp.body().string());
                if (b.optBoolean("status") && !b.isNull("result")) {
                    return b.optString("result", "");
                }
            }
            return "";
        }
    }

    // ===== 按需解析图片 URL（懒加载：随列表卡片渲染分批触发） =====

    public interface ImageUrlCallback {
        /** 解析成功；url 可能为空串 */
        void onUrl(String url);
        /** 解析失败 */
        void onError(String error);
    }

    /**
     * 异步解析单个包裹的图片 URL（getEncryptFileUrl）。
     * 由界面在渲染卡片时按需调用：首屏只解析前一批，往下滑动渲染下一批时再解析下一批，
     * 不再像以前那样查询后一次性并发请求全部包裹的图片 URL。
     */
    public void resolveImageUrl(final String billCode, final String imgPath, final ImageUrlCallback callback) {
        if (callback == null) return;
        if (billCode == null || imgPath == null || billCode.length() == 0 || imgPath.length() == 0) {
            callback.onError("缺少单号或图片路径");
            return;
        }
        new Thread(() -> {
            try {
                JSONObject auth = ensureLogin();
                String url = getEncryptFileUrl(billCode, imgPath, auth);
                if (url != null && url.length() > 0) {
                    callback.onUrl(url);
                } else {
                    callback.onError("图片URL解析为空");
                }
            } catch (Exception e) {
                callback.onError(e == null ? "图片URL解析异常" : e.getMessage());
            }
        }, "img-url-" + Math.abs(billCode.hashCode())).start();
    }

    /** 构建 getEncryptFileUrl 请求对象（不执行），供并发场景复用 */
    private Request buildEncryptFileUrlRequest(String billCode, String fileName, JSONObject auth) throws Exception {
        JSONObject data = new JSONObject();
        data.put("billCode", billCode);
        data.put("fileName", fileName);
        String postData = "data=" + URLEncoder.encode(data.toString(), "UTF-8");

        String ysDt = auth.optString("ysDt", "05245f012f64472996fb71e2e8b1b97c_4n6zM8Mqn9VECxVAAQbDvg2R89+WANI+");
        return new Request.Builder()
                .url("https://kdcs-wx-yd.zt-express.com/gateway.do/")
                .header("X-Zop-Name", "getEncryptFileUrl")
                .header("X-Sv-V", "com.zto.ztoFamilyAPPStore_4.51.4")
                .header("X-Ca-Version", "1")
                .header("x-iam-token", auth.getString("accessToken"))
                .header("X-Unionid", "unionB-wgxs_oGtI1bbTV4rRKB")
                .header("X-Device-Id", "1750F6BE-5052-4FE6-9579-E8AF49522BA4")
                .header("X-Userid", auth.getString("userId"))
                .header("X-App-Version", "4.51.4")
                .header("X-Ys-Dt", ysDt)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .post(RequestBody.create(postData, MediaType.parse("application/x-www-form-urlencoded")))
                .build();
    }

    // ===== getStockBillInfo：兔喜app详情API（用于超时件图片反查） =====

    /**
     * 获取包裹详情（兔喜 app 使用的 API：tuxi.spm.read.detail.getStockBillInfo）。
     * 当 queryScanEnterInfoAppByCode 返回 fileImgPath:null 时，此 API 可能返回 synSignImage / envImageInfos 等不同图片字段。
     * @return 图片 URL，无图片则返回空字符串
     */
    private String getStockBillInfo(String billCode, JSONObject auth) throws Exception {
        JSONObject data = new JSONObject();
        data.put("expressCompanyCode", "ZTO");
        data.put("billCode", billCode);
        data.put("queryScene", "APP_BILl_INFO_DETAIL");
        String postData = "data=" + URLEncoder.encode(data.toString(), "UTF-8");

        String token = auth.optString("accessToken", "");
        String userId = auth.optString("userId", "");
        String unionId = auth.optString("unionId", "unionB-wgxs_oGtI1bbTV4rRKB");
        String deviceId = auth.optString("deviceId", "1750F6BE-5052-4FE6-9579-E8AF49522BA4");
        String ysDt = auth.optString("ysDt", "05245f012f64472996fb71e2e8b1b97c_4n6zM8Mqn9VECxVAAQbDvg2R89+WANI+");

        Request request = new Request.Builder()
                .url("https://kdcs-wx-yd.zt-express.com/gateway.do/")
                .header("X-Zop-Name", "tuxi.spm.read.detail.getStockBillInfo")
                .header("X-Sv-V", "com.zto.ztoFamilyAPPStore_4.51.4")
                .header("X-Ca-Version", "1")
                .header("x-iam-token", token)
                .header("token", token)
                .header("X-Unionid", unionId)
                .header("X-Device-Id", deviceId)
                .header("X-Userid", userId)
                .header("X-App-Version", "4.51.4")
                .header("X-Ys-Dt", ysDt)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .post(RequestBody.create(postData, MediaType.parse("application/x-www-form-urlencoded")))
                .build();

        try (Response resp = client.newCall(request).execute()) {
            if (resp.body() == null) return "";
            JSONObject body = new JSONObject(resp.body().string());
            if (!body.optBoolean("status") || body.isNull("result")) return "";
            JSONObject r = body.getJSONObject("result");

            // 途径1: synSignImage
            String synImg = r.optString("synSignImage", "");
            if (synImg.length() > 0) {
                try {
                    String url = getEncryptFileUrl(billCode, synImg, auth);
                    if (url.length() > 0) return url;
                } catch (Exception e) { /* ignore */ }
            }

            // 途径2: envImageInfos 数组
            JSONArray envImgs = r.optJSONArray("envImageInfos");
            if (envImgs != null && envImgs.length() > 0) {
                JSONObject firstImg = envImgs.getJSONObject(0);
                String imgName = firstNonEmpty(
                        firstImg.optString("fileName", ""),
                        firstImg.optString("imgName", ""),
                        firstImg.optString("imageName", ""),
                        firstImg.optString("path", ""),
                        firstImg.optString("url", ""));
                if (imgName.length() > 0) {
                    try {
                        String url = getEncryptFileUrl(billCode, imgName, auth);
                        if (url.length() > 0) return url;
                    } catch (Exception e) { /* ignore */ }
                }
            }

            // 途径3: fileImgPath / inSignImg 兜底
            String fallback = firstNonEmpty(
                    r.optString("fileImgPath", ""),
                    r.optString("inSignImg", ""));
            if (fallback.length() > 0) {
                try {
                    String url = getEncryptFileUrl(billCode, fallback, auth);
                    if (url.length() > 0) return url;
                } catch (Exception e) { /* ignore */ }
            }

            return "";
        } catch (Exception e) {
            return "";
        }
    }

    /** 按单号查询包裹轨迹（tuxi.spm.read.detail.getStockBillLog，与兔喜官方app一致），返回 result 数组或 null */
    private JSONArray queryStockBillLog(String billCode, String expressCompanyCode) throws Exception {
        JSONObject auth = ensureLogin();
        JSONObject data = new JSONObject();
        data.put("uploadDate", System.currentTimeMillis());
        data.put("billCode", billCode);
        data.put("expressCompanyCode",
                (expressCompanyCode == null || expressCompanyCode.isEmpty()) ? "ZTO" : expressCompanyCode);
        String postData = "data=" + URLEncoder.encode(data.toString(), "UTF-8");

        String token = auth.optString("accessToken", "");
        String userId = auth.optString("userId", "");
        String unionId = auth.optString("unionId", "unionB-wgxs_oGtI1bbTV4rRKB");
        String deviceId = auth.optString("deviceId", "1750F6BE-5052-4FE6-9579-E8AF49522BA4");
        String ysDt = auth.optString("ysDt", "05245f012f64472996fb71e2e8b1b97c_4n6zM8Mqn9VECxVAAQbDvg2R89+WANI+");

        Request request = new Request.Builder()
                .url("https://kdcs-wx-lt.zt-express.com/gateway.do/")
                .header("X-Zop-Name", "tuxi.spm.read.detail.getStockBillLog")
                .header("X-Sv-V", "com.zto.ztoFamilyAPPStore_4.51.4")
                .header("X-Ca-Version", "1")
                .header("x-iam-token", token)
                .header("token", token)
                .header("X-Unionid", unionId)
                .header("X-Device-Id", deviceId)
                .header("X-Userid", userId)
                .header("X-App-Version", "4.51.4")
                .header("X-Ys-Dt", ysDt)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .post(RequestBody.create(postData, MediaType.parse("application/x-www-form-urlencoded")))
                .build();

        try (Response resp = client.newCall(request).execute()) {
            if (resp.body() == null) return null;
            JSONObject body = new JSONObject(resp.body().string());
            if (!body.optBoolean("status", false) || body.isNull("result")) return null;
            return body.getJSONArray("result");
        }
    }

    /**
     * 轨迹里提取出入库照片（供预览对比）：{arrival, outbound} 原始S3路径，缺失为空串。
     * 入库图取 stockLogTypeCode=12/10，出库图取 =50（签收出库）。
     */
    public JSONObject getStockBillLog(String billCode, String expressCompanyCode) throws Exception {
        JSONObject out = new JSONObject();
        out.put("arrival", "");
        out.put("outbound", "");
        JSONArray logs = queryStockBillLog(billCode, expressCompanyCode);
        if (logs == null) return out;
        for (int i = 0; i < logs.length(); i++) {
            JSONObject item = logs.optJSONObject(i);
            if (item == null) continue;
            String type = item.optString("stockLogTypeCode", "");
            String img = item.optString("image", "");
            if (img == null || img.isEmpty()) continue;
            if (out.optString("arrival").isEmpty() && ("12".equals(type) || "10".equals(type))) {
                out.put("arrival", img);
            } else if ("50".equals(type)) {
                out.put("outbound", img);
            }
        }
        return out;
    }

    /** 完整包裹轨迹（供轨迹详情弹窗展示）：返回 result 数组，可能为 null */
    public JSONArray getStockBillLogEntries(String billCode, String expressCompanyCode) throws Exception {
        return queryStockBillLog(billCode, expressCompanyCode);
    }

    /**
     * 单号反查：用 billCode 直接调用 queryScanEnterInfoAppByCode 获取单条包裹的 fileImgPath。
     * 不与 queryPackages() 互相递归。
     * @return fileImgPath / inSignImg 的值（可能为空），失败或无结果返回 null
     */
    private String reverseLookupBillCode(String billCode, JSONObject auth) throws Exception {
        SimpleDateFormat sdfEnd = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        String endDate = sdfEnd.format(new Date());

        String token = auth.optString("accessToken", "");
        String userId = auth.optString("userId", "");
        String unionId = auth.optString("unionId", "unionB-wgxs_oGtI1bbTV4rRKB");
        String deviceId = auth.optString("deviceId", "1750F6BE-5052-4FE6-9579-E8AF49522BA4");
        String ysDt = auth.optString("ysDt", "05245f012f64472996fb71e2e8b1b97c_4n6zM8Mqn9VECxVAAQbDvg2R89+WANI+");

        JSONObject queryData = new JSONObject();
        queryData.put("pageSize", 1);
        queryData.put("billCode", JSONObject.NULL);
        queryData.put("leaveRemark", JSONObject.NULL);
        queryData.put("depotCode", DEPOT_CODE);
        queryData.put("type", 2);
        queryData.put("endDate", endDate);
        queryData.put("code", billCode);
        queryData.put("queryScene", "APP_INDEX");
        queryData.put("expressCompanyCode", JSONObject.NULL);
        queryData.put("grayFlag", "Y");
        queryData.put("page", 1);
        queryData.put("dateRange", 30);

        String postData = "data=" + URLEncoder.encode(queryData.toString(), "UTF-8");

        Request request = new Request.Builder()
                .url("https://kdcs-wx-yd.zt-express.com/gateway.do/")
                .header("X-Zop-Name", "tuxi.spm.stock.read.queryScanEnterInfoAppByCode")
                .header("X-Sv-V", "com.zto.ztoFamilyAPPStore_4.51.4")
                .header("X-Ca-Version", "6")
                .header("x-iam-token", token)
                .header("token", token)
                .header("X-Unionid", unionId)
                .header("X-Device-Id", deviceId)
                .header("X-Userid", userId)
                .header("X-App-Version", "4.51.4")
                .header("X-Ys-Dt", ysDt)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .post(RequestBody.create(postData, MediaType.parse("application/x-www-form-urlencoded")))
                .build();

        try (Response resp = client.newCall(request).execute()) {
            if (resp.body() == null) return null;
            JSONObject body = new JSONObject(resp.body().string());
            if (!body.optBoolean("status") || body.isNull("result")) return null;
            JSONObject r = body.getJSONObject("result");
            // 提取 stockInfos
            JSONArray infos = r.optJSONArray("stockInfos");
            if (infos == null) infos = r.optJSONArray("list");
            if (infos == null) infos = r.optJSONArray("items");
            if (infos == null) infos = r.optJSONArray("data");
            if (infos == null) {
                // 可能直接是单个对象
                infos = new JSONArray();
                infos.put(r);
            }
            if (infos.length() == 0) return null;
            JSONObject first = infos.getJSONObject(0);
            return firstNonEmpty(
                    first.optString("fileImgPath", ""),
                    first.optString("inSignImg", ""));
        } catch (Exception e) {
            return null;
        }
    }

    // ===== Query Packages =====

    /** 向后兼容的二参数版本，pendingOnly 默认为 false */
    public JSONObject queryPackages(String search, String type) throws Exception {
        return queryPackages(search, type, false);
    }

    public JSONObject queryPackages(String search, String type, boolean pendingOnly) throws Exception {
        Log.d(TAG, "直接查询包裹: search=" + search + " type=" + type);
        try { LogRecorder.info(appContext, "DirectApi", "查询包裹", "search=" + search + " type=" + type); } catch (Exception ignore) {}
        JSONObject auth = ensureLogin();

        String s = search == null ? "" : search.trim();
        int numType;
        String code;

        // type=1: 手机尾号查询, type=2: 单号查询, type=3: 取件码查询
        if ("1".equals(type) || "phoneTail".equals(type)) {
            numType = 1;
            // 与独立app抓包（包裹查询.hcy）和 server.js 对齐：手机尾号查询传纯4位数字，不加 ******* 前缀
            // 独立app: code="2979"+type=1 → 返回90+条；加前缀会被服务器严格过滤，只剩极少数严格匹配的记录
            String digits = s.replaceAll("\\D", "");
            code = digits.length() >= 4 ? digits.substring(digits.length() - 4) : digits;
        } else if ("2".equals(type) || "billCode".equals(type) || "waybillCode".equals(type)) {
            numType = 2;
            code = s;
        } else if ("3".equals(type) || "pickupCode".equals(type) || "takeCode".equals(type)) {
            numType = 3;
            code = s;
        } else {
            // 自动识别
            if (s.matches("^\\d{4}$")) {
                numType = 1;
                // 同上：手机尾号查询传纯4位数字，不加 ******* 前缀（与独立app/server.js对齐）
                code = s;
            } else if (s.matches("^\\d{4,25}$")) {
                numType = 2;
                code = s;
            } else {
                numType = 3;
                code = s;
            }
        }

        // 生成endDate：例如 2026-07-31 18:30:00
        SimpleDateFormat sdfEnd = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        String endDate = sdfEnd.format(new Date());

        // 统一请求参数配置
        final int PAGE_SIZE = 500;
        final int DATE_RANGE = 30;
        final int MAX_PAGES = 20;
        final String HOST = "kdcs-wx-yd.zt-express.com";
        final String X_ZOP_NAME = "tuxi.spm.stock.read.queryScanEnterInfoAppByCode";

        String accessToken = auth.optString("accessToken", "");
        String userId = auth.optString("userId", "");
        String unionId = auth.optString("unionId", "unionB-wgxs_oGtI1bbTV4rRKB");
        String deviceId = auth.optString("deviceId", "1750F6BE-5052-4FE6-9579-E8AF49522BA4");
        String ysDt = auth.optString("ysDt", "05245f012f64472996fb71e2e8b1b97c_4n6zM8Mqn9VECxVAAQbDvg2R89+WANI+");

        JSONArray allStockInfos = new JSONArray();
        Integer totalCount = null;

        // 分页循环
        pageLoop:
        for (int page = 1; page <= MAX_PAGES; page++) {
            // 构造请求体：与服务端server.js保持一致
            JSONObject queryData = new JSONObject();
            queryData.put("pageSize", PAGE_SIZE);
            queryData.put("billCode", JSONObject.NULL);
            queryData.put("leaveRemark", JSONObject.NULL);
            queryData.put("depotCode", DEPOT_CODE);
            queryData.put("type", numType);
            queryData.put("endDate", endDate);
            queryData.put("code", code);
            queryData.put("queryScene", "APP_INDEX");
            queryData.put("expressCompanyCode", JSONObject.NULL);
            queryData.put("grayFlag", "Y");
            queryData.put("page", page);
            queryData.put("dateRange", DATE_RANGE);

            String postData = "data=" + URLEncoder.encode(queryData.toString(), "UTF-8");

            Request request = new Request.Builder()
                    .url("https://" + HOST + "/gateway.do/")
                    .header("X-Zop-Name", X_ZOP_NAME)
                    .header("X-Sv-V", "com.zto.ztoFamilyAPPStore_4.51.4")
                    .header("X-Ca-Version", "6")
                    .header("x-iam-token", accessToken)
                    .header("token", accessToken)
                    .header("X-Userid", userId)
                    .header("X-Unionid", unionId)
                    .header("X-Device-Id", deviceId)
                    .header("X-Ys-Dt", ysDt)
                    .header("X-App-Version", "4.51.4")
                    .post(RequestBody.create(postData, MediaType.parse("application/x-www-form-urlencoded")))
                    .build();

            JSONObject body;
            try (Response resp = client.newCall(request).execute()) {
                if (resp.body() == null) {
                    if (page == 1) {
                        Exception e = new Exception("查询响应为空");
                        try { LogRecorder.error(appContext, "DirectApi", "查询失败", e.getMessage()); } catch (Exception ignore) {}
                        throw e;
                    } else {
                        Log.w(TAG, "第" + page + "页响应为空，停止分页，返回已获取数据: " + allStockInfos.length() + " 条");
                        break;
                    }
                }
                body = new JSONObject(resp.body().string());
            } catch (Exception e) {
                if (page == 1) throw e;
                Log.w(TAG, "第" + page + "页请求异常，停止分页: " + e.getMessage());
                break;
            }

            if (page == 1) {
                int logLen = Math.min(300, body.toString().length());
                Log.d(TAG, "第1页查询返回: " + body.toString().substring(0, logLen));
            } else {
                int logLen = Math.min(150, body.toString().length());
                Log.d(TAG, "第" + page + "页查询返回: " + body.toString().substring(0, logLen));
            }

            if (!body.optBoolean("status") || body.isNull("result")) {
                String msg = body.optString("message", "未知错误");
                if (page == 1) {
                    try { LogRecorder.error(appContext, "DirectApi", "查询失败", msg); } catch (Exception ignore) {}
                    // 检测token过期
                    if (msg.contains("token") || msg.contains("登录") || msg.contains("未授权") || msg.contains("过期") || msg.contains("无效") || msg.contains("令牌")) {
                        accessToken = null;
                        userId = null;
                        tokenExpiresAt = 0;
                    }
                    throw new Exception("查询失败: " + msg);
                } else {
                    // 后续页业务失败，退出循环，用已拿到的数据
                    Log.w(TAG, "第" + page + "页业务失败，停止分页: " + msg);
                    break;
                }
            }

            JSONObject result = body.getJSONObject("result");

            // 第1页尝试读取总数字段
            if (totalCount == null) {
                totalCount = firstPositiveInt(
                        result.opt("totalCount"),
                        result.opt("recordCount"),
                        result.opt("total"),
                        result.opt("count"),
                        result.opt("stockCount"));
                // 嵌套格式 result.stockInfos.totalCount 等
                if (totalCount == null && result.has("stockInfos") && !result.isNull("stockInfos")) {
                    Object siObj = result.opt("stockInfos");
                    if (siObj instanceof JSONObject) {
                        JSONObject siJo = (JSONObject) siObj;
                        totalCount = firstPositiveInt(
                                siJo.opt("totalCount"),
                                siJo.opt("recordCount"),
                                siJo.opt("total"),
                                siJo.opt("count"));
                    }
                }
            }

            // 读取当前页stockInfos
            JSONArray pageStockInfos = new JSONArray();
            if (result.has("stockInfos") && !result.isNull("stockInfos")) {
                Object si = result.get("stockInfos");
                if (si instanceof JSONArray) {
                    pageStockInfos = (JSONArray) si;
                } else if (si instanceof JSONObject && ((JSONObject) si).has("stockInfos")) {
                    pageStockInfos = ((JSONObject) si).getJSONArray("stockInfos");
                }
            }

            Log.d(TAG, "第" + page + "页 stockInfos数量=" + pageStockInfos.length()
                    + "（累计:" + (allStockInfos.length() + pageStockInfos.length())
                    + " / 总数:" + (totalCount == null ? "未知" : totalCount) + "）");

            for (int i = 0; i < pageStockInfos.length(); i++) {
                allStockInfos.put(pageStockInfos.get(i));
            }

            // 判断是否还有下一页
            int thisPageCount = pageStockInfos.length();
            if (totalCount != null && allStockInfos.length() >= totalCount) {
                Log.d(TAG, "已达到服务端总数(" + allStockInfos.length() + "/" + totalCount + ")，停止分页");
                break;
            }
            if (thisPageCount < PAGE_SIZE) {
                Log.d(TAG, "第" + page + "页返回" + thisPageCount + "条 < pageSize=" + PAGE_SIZE + "，已到最后一页");
                break;
            }
            // 否则继续下一页
        }

        Log.d(TAG, "分页完成，共 " + allStockInfos.length() + " 条数据");
        try {
            LogRecorder.info(appContext, "DirectApi", "查询完成",
                    "搜索=" + s + ",type=" + type + ",条数=" + allStockInfos.length()
                            + (totalCount == null ? "" : "(服务端总数=" + totalCount + ")"));
        } catch (Exception ignore) {}

        // pendingOnly：自动刷新模式下，只保留待取件（status=1），跳过已出库的图片获取以节省资源
        if (pendingOnly) {
            int before = allStockInfos.length();
            JSONArray filtered = new JSONArray();
            for (int i = 0; i < allStockInfos.length(); i++) {
                JSONObject item = allStockInfos.getJSONObject(i);
                int sv = item.optInt("status", 1);
                if (sv == 1) filtered.put(item);
            }
            if (filtered.length() < before) {
                Log.d(TAG, "pendingOnly 过滤: " + before + " → " + filtered.length() + " 条（仅保留待取件）");
            }
            allStockInfos = filtered;
        }

        // Phase 3: 组装结果。
        // 图片 URL 不再在此一次性全部解析，只携带原始图片路径（rawImgPath），
        // 由界面"渲染到哪张卡片、才解析哪张的 URL"（随滚动批次），避免一次性请求全部图片 URL。
        JSONArray packages = new JSONArray();
        for (int i = 0; i < allStockInfos.length(); i++) {
            JSONObject item = allStockInfos.getJSONObject(i);
            JSONObject pkg = new JSONObject();
            String billCode = firstNonEmpty(
                    item.optString("billCode", ""),
                    item.optString("trackingNumber", ""),
                    item.optString("waybillCode", ""));
            String receiveMan = firstNonEmpty(
                    item.optString("receiveMan", ""),
                    item.optString("recipientName", ""),
                    item.optString("receiver", ""));
            if (receiveMan.length() == 0) receiveMan = "未知";
            String takeCode = firstNonEmpty(
                    item.optString("takeCode", ""),
                    item.optString("pickupCode", ""));
            String courier = firstNonEmpty(
                    item.optString("expressCompanyName", ""),
                    item.optString("expressCompany", ""),
                    item.optString("express", ""));
            // 入库时间
            String arrivedAt = firstNonEmpty(
                    item.optString("billCodeScanTime", ""),
                    item.optString("stockInDate", ""),
                    item.optString("scanTime", ""),
                    item.optString("createTime", ""));
            // 出库时间 takeDate
            String takeDate = firstNonEmpty(
                    item.optString("takeDate", ""),
                    item.optString("outboundDate", ""),
                    item.optString("takeTime", ""));
            // 收件人手机号
            String receiveManMobile = firstNonEmpty(
                    item.optString("receiveManMobile", ""),
                    item.optString("receiveManPhone", ""),
                    item.optString("phone", ""),
                    item.optString("mobile", ""));
            // 状态：1=待取/入库, 2=已出库/已取件
            int statusVal = item.optInt("status", 0);
            String statusStr = statusVal == 2 ? "delivered" : "pending";

            pkg.put("id", String.valueOf(item.opt("id") == null || JSONObject.NULL.equals(item.opt("id"))
                    ? (billCode.length() > 0 ? billCode : String.valueOf(System.currentTimeMillis() + i))
                    : item.optString("id", "")));
            pkg.put("billCode", billCode);
            pkg.put("trackingNumber", billCode);
            pkg.put("recipientName", receiveMan);
            pkg.put("receiveMan", receiveMan);
            pkg.put("pickupCode", takeCode);
            pkg.put("takeCode", takeCode);
            pkg.put("courier", courier);
            pkg.put("express", courier);
            pkg.put("expressCompany", courier);
            pkg.put("expressCompanyName", courier);
            pkg.put("status", statusStr);
            pkg.put("arrivedAt", arrivedAt);
            pkg.put("time", arrivedAt);
            pkg.put("createTime", arrivedAt);
            pkg.put("billCodeScanTime", arrivedAt);
            pkg.put("takeDate", takeDate);
            pkg.put("outboundTime", takeDate);
            pkg.put("receiveManMobile", receiveManMobile);
            pkg.put("remark", item.optString("remark", ""));
            Object leaveType = item.opt("leaveType");
            if (leaveType != null && !JSONObject.NULL.equals(leaveType)) {
                pkg.put("leaveType", leaveType);
            }
            String rawImgPath = firstNonEmpty(
                    item.optString("fileImgPath", ""),
                    item.optString("inSignImg", ""),
                    item.optString("imgName", ""));
            // 出入库照片对比（按兔喜官方数据核对）：inSignImg=入库签收照，fileImgPath=出库文件照
            String rawArrival = firstNonEmpty(
                    item.optString("inSignImg", ""),
                    item.optString("imgName", ""));
            String rawOutbound = firstNonEmpty(
                    item.optString("fileImgPath", ""),
                    item.optString("outSignImg", ""));
            pkg.put("imageUrl", "");
            pkg.put("imgUrl", "");
            pkg.put("rawImgPath", rawImgPath);
            pkg.put("rawImgPathArrival", rawArrival);
            pkg.put("rawImgPathOutbound", rawOutbound);
            pkg.put("expressCompanyCode", item.optString("expressCompanyCode", ""));
            packages.put(pkg);
        }

        JSONObject response = new JSONObject();
        response.put("ok", true);
        response.put("data", packages);
        return response;
    }

    // ===== Query Timeout Packages =====

    public JSONArray queryTimeoutPackages() throws Exception {
        Log.d(TAG, "直接查询超时件");
        try { LogRecorder.info(appContext, "DirectApi", "查询超时件", ""); } catch (Exception ignore) {}
        JSONObject auth = ensureLogin();

        JSONObject queryData = new JSONObject();
        queryData.put("pageIndex", 1);
        queryData.put("pageSize", 100);
        queryData.put("queryDays", JSONObject.NULL);
        queryData.put("depotCode", DEPOT_CODE);

        String postData = "data=" + URLEncoder.encode(queryData.toString(), "UTF-8");

        Request request = new Request.Builder()
                .url("https://kdcs-wx-yd.zt-express.com/gateway.do/")
                .header("X-Zop-Name", "tuxi.spm.stock.queryTimeOutStockList")
                .header("X-Sv-V", "com.zto.ztoFamilyAPPStore_4.51.4")
                .header("X-Ca-Version", "1")
                .header("x-iam-token", auth.getString("accessToken"))
                .header("X-Userid", auth.getString("userId"))
                .header("X-Unionid", "unionB-wgxs_oGtI1bbTV4rRKB")
                .header("X-Device-Id", "1750F6BE-5052-4FE6-9579-E8AF49522BA4")
                .header("X-App-Version", "4.51.4")
                .header("X-Ys-Dt", "05245f012f64472996fb71e2e8b1b97c_e+jQZdUy2qRAT1BBQVKDvplwIuZe3grv")
                .post(RequestBody.create(postData, MediaType.parse("application/x-www-form-urlencoded")))
                .build();

        try (Response resp = client.newCall(request).execute()) {
            if (resp.body() == null) {
                Exception e = new Exception("超时查询响应为空");
                try { LogRecorder.error(appContext, "DirectApi", "超时查询失败", e.getMessage()); } catch (Exception ignore) {}
                throw e;
            }
            JSONObject body = new JSONObject(resp.body().string());
            int rc = 0;
            try { rc = body.optJSONObject("result").optInt("recordCount", 0); } catch (Exception ignore) {}
            Log.d(TAG, "超时查询返回记录数: " + rc);
            try { LogRecorder.info(appContext, "DirectApi", "超时查询返回", "记录数=" + rc); } catch (Exception ignore) {}

            if (!body.optBoolean("status") || body.isNull("result")) {
                String msg = body.optString("message", "未知错误");
                try { LogRecorder.error(appContext, "DirectApi", "超时查询失败", msg); } catch (Exception ignore) {}
                throw new Exception("超时查询失败: " + msg);
            }

            JSONObject result = body.getJSONObject("result");
            JSONArray items = result.optJSONArray("items");
            if (items == null) items = new JSONArray();

            // Phase 1: 收集所有需要图片的项（直接有图片路径 + 仅有单号需反查）
            final String[] imgResults = new String[items.length()];
            int totalTasks = 0;
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i);
                String bc = item.optString("billCode", "");
                String ip = firstNonEmpty(
                        item.optString("fileImgPath", ""),
                        item.optString("inSignImg", ""),
                        item.optString("imgName", ""));
                if (bc.length() > 0 && ip.length() > 0) totalTasks++;
                else if (bc.length() > 0) totalTasks++; // 超时接口无图片路径，按单号反查
                else imgResults[i] = "";
            }

            // Phase 2: 并发获取图片 URL（直接路径 + 单号反查两条路径混合）
            if (totalTasks > 0) {
                final CountDownLatch latch = new CountDownLatch(totalTasks);
                final JSONObject authFinal = auth;
                final java.util.concurrent.ExecutorService billExecutor = java.util.concurrent.Executors.newFixedThreadPool(10);
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.getJSONObject(i);
                    String bc = item.optString("billCode", "");
                    String ip = firstNonEmpty(
                            item.optString("fileImgPath", ""),
                            item.optString("inSignImg", ""),
                            item.optString("imgName", ""));
                    if (bc.length() == 0) continue;
                    final int idx = i;
                    imgResults[idx] = ""; // 默认空串，避免 latch 超时后为 null
                    if (ip.length() > 0) {
                        // 有直接图片路径 → getEncryptFileUrl
                        final String billCode = bc;
                        final String imgPath = ip;
                        Request imgReq;
                        try {
                            imgReq = buildEncryptFileUrlRequest(billCode, imgPath, authFinal);
                        } catch (Exception e) {
                            imgResults[idx] = "";
                            latch.countDown();
                            continue;
                        }
                        client.newCall(imgReq).enqueue(new Callback() {
                            @Override public void onFailure(Call call, IOException e) {
                                imgResults[idx] = "";
                                latch.countDown();
                            }
                            @Override public void onResponse(Call call, Response response) throws IOException {
                                try {
                                    if (response.body() != null) {
                                        JSONObject b = new JSONObject(response.body().string());
                                        imgResults[idx] = (b.optBoolean("status") && !b.isNull("result"))
                                                ? b.optString("result", "") : "";
                                    } else imgResults[idx] = "";
                                } catch (Exception e) { imgResults[idx] = ""; }
                                finally { response.close(); }
                                latch.countDown();
                            }
                        });
                    } else {
                        // 无图片路径 → 两级反查：
                        //  1) queryScanEnterInfoAppByCode（单号查询）
                        //  2) getStockBillInfo（兔喜app详情API，可能返回 synSignImage / envImageInfos）
                        final String billCode = bc;
                        billExecutor.submit(() -> {
                            String imgUrl = "";
                            try {
                                // 第一级：queryScanEnterInfoAppByCode
                                JSONObject pkgResp = queryPackages(billCode, "billCode");
                                JSONArray data = pkgResp.optJSONArray("data");
                                if (data != null && data.length() > 0) {
                                    imgUrl = firstNonEmpty(
                                            data.getJSONObject(0).optString("imageUrl", ""),
                                            data.getJSONObject(0).optString("imgUrl", ""));
                                }

                                // 第二级：getStockBillInfo（兔喜app详情API）
                                if (imgUrl.length() == 0) {
                                    try {
                                        imgUrl = getStockBillInfo(billCode, authFinal);
                                    } catch (Exception e2) { /* ignore */ }
                                }
                            } catch (Exception e) { /* ignore */ } finally {
                                imgResults[idx] = imgUrl;
                                latch.countDown();
                            }
                        });
                    }
                }
                try { latch.await(30, TimeUnit.SECONDS); } catch (InterruptedException e) { Log.w(TAG, "超时件图片/反查并发获取被中断"); }
                billExecutor.shutdown();
            }

            // Phase 3: 组装结果
            JSONArray packages = new JSONArray();
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i);
                JSONObject pkg = new JSONObject();
                String billCode = item.optString("billCode", "");
                pkg.put("billCode", billCode);
                pkg.put("trackingNumber", billCode);
                pkg.put("receiveMan", item.optString("receiveMan", "未知"));
                pkg.put("recipientName", item.optString("receiveMan", "未知"));
                pkg.put("arrivedAt", item.optString("registerDate", ""));
                pkg.put("time", item.optString("registerDate", ""));
                pkg.put("courier", item.optString("expressCompanyName", ""));
                pkg.put("express", item.optString("expressCompanyName", ""));
                pkg.put("takeCode", item.optString("takeCode", ""));
                pkg.put("pickupCode", item.optString("takeCode", ""));
                String imageUrl = (imgResults[i] != null) ? imgResults[i] : "";
                pkg.put("imageUrl", imageUrl);
                pkg.put("imgUrl", imageUrl);
                packages.put(pkg);
            }

            return packages;
        }
    }

    private static String firstNonEmpty(String... arr) {
        if (arr == null) return "";
        for (String s : arr) {
            if (s != null && s.length() > 0 && !"null".equalsIgnoreCase(s)) return s;
        }
        return "";
    }

    /**
     * 从多个候选Object中读取第一个存在的正整数（用于读取服务端返回的总数字段）。
     * 支持 Integer / Long / Number / 可解析数字字符串 类型。
     */
    private static Integer firstPositiveInt(Object... arr) {
        if (arr == null) return null;
        for (Object o : arr) {
            if (o == null || JSONObject.NULL.equals(o)) continue;
            try {
                int v;
                if (o instanceof Integer) v = (Integer) o;
                else if (o instanceof Long) v = ((Long) o).intValue();
                else if (o instanceof Number) v = ((Number) o).intValue();
                else {
                    String str = String.valueOf(o).trim();
                    if (str.length() == 0) continue;
                    v = Integer.parseInt(str);
                }
                if (v > 0) return v;
            } catch (Exception ignore) {}
        }
        return null;
    }

    // ===== Timeout Outbound（超时件直连出库，不依赖电脑端） =====

    public interface OutboundCallback {
        void onSuccess(JSONObject response);
        void onError(String error);
    }

    /**
     * 超时件直连出库：按抓包文件"将超时件出库.har"构造请求。
     * remark 固定为"超时出库"，lation 固定坐标，takeDate 取当前毫秒时间戳。
     * 回调在主线程派发，可直接更新 UI。
     */
    public void outboundTimeoutPackage(final String billCode, final String receiveMan, final OutboundCallback callback) {
        if (callback == null) return;
        new Thread(() -> {
            try {
                JSONObject auth = ensureLogin();
                JSONObject data = new JSONObject();
                data.put("receiveMan", receiveMan == null ? "" : receiveMan);
                data.put("lation", TIMEOUT_OUTBOUND_LATION);
                data.put("takeDate", System.currentTimeMillis());
                data.put("billCode", billCode == null ? "" : billCode);
                data.put("remark", TIMEOUT_OUTBOUND_REMARK);

                String postData = "data=" + URLEncoder.encode(data.toString(), "UTF-8");

                String ysDt = auth.optString("ysDt", "05245f012f64472996fb71e2e8b1b97c_4n6zM8Mqn9VECxVAAQbDvg2R89+WANI+");
                String unionId = auth.optString("unionId", "unionB-wgxs_oGtI1bbTV4rRKB");
                String deviceId = auth.optString("deviceId", "1750F6BE-5052-4FE6-9579-E8AF49522BA4");

                Request request = new Request.Builder()
                        .url(TIMEOUT_OUTBOUND_URL)
                        .header("X-Zop-Name", "tuxi.spm.stock.outbound")
                        .header("X-Sv-V", "com.zto.ztoFamilyNew_4.44.0")
                        .header("X-Ca-Version", "1")
                        .header("x-iam-token", auth.getString("accessToken"))
                        .header("X-Userid", auth.getString("userId"))
                        .header("X-Unionid", unionId)
                        .header("X-Device-Id", deviceId)
                        .header("X-Ys-Dt", ysDt)
                        .header("X-App-Version", "4.44.0")
                        .header("User-Agent", "wanjiaExpress/4.44.0 (iPhone; iOS 26.1; Scale/3.00)")
                        .post(RequestBody.create(postData, MediaType.parse("application/x-www-form-urlencoded")))
                        .build();

                JSONObject body;
                try (Response resp = client.newCall(request).execute()) {
                    if (resp.body() == null) {
                        Exception e = new Exception("出库响应为空");
                        try { LogRecorder.error(appContext, "DirectApi", "超时出库失败", e.getMessage()); } catch (Exception ignore) {}
                        throw e;
                    }
                    body = new JSONObject(resp.body().string());
                }
                Log.d(TAG, "超时出库返回: " + body.toString());
                try { LogRecorder.info(appContext, "DirectApi", "超时出库返回", body.toString()); } catch (Exception ignore) {}

                // 成功判定：顶层 status 与 result.status 均为 true（抓包响应结构）
                boolean ok = body.optBoolean("status", false);
                JSONObject result = body.optJSONObject("result");
                if (result != null) {
                    ok = ok && result.optBoolean("status", false);
                } else {
                    ok = false;
                }
                if (ok) {
                    mainHandler.post(() -> callback.onSuccess(body));
                    return;
                }

                String msg = (result != null) ? result.optString("failReason", "") : "";
                if (msg.length() == 0) msg = body.optString("message", "未知错误");
                // token 过期检测：命中关键词则清缓存，下次自动重登
                if (msg.contains("token") || msg.contains("登录") || msg.contains("未授权")
                        || msg.contains("过期") || msg.contains("无效") || msg.contains("令牌")) {
                    accessToken = null;
                    userId = null;
                    tokenExpiresAt = 0;
                }
                final String fMsg = msg;
                mainHandler.post(() -> callback.onError(fMsg));
            } catch (Exception e) {
                final String err = (e == null || e.getMessage() == null) ? "出库异常" : e.getMessage();
                mainHandler.post(() -> callback.onError(err));
            }
        }, "timeout-outbound").start();
    }
}
