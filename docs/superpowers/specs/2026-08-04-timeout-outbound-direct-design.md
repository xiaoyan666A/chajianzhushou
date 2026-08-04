# 超时件直连出库（不再依赖电脑端）· 设计文档

日期：2026-08-04
项目：查件助手（com.chajianzhushou.app）
状态：已获用户确认

## 背景与目标

超时件页的"立即超时出库"按钮目前调用电脑端接口 `/api/timeout/outbound`（ApiService），由 PC 代理执行出库。现改为按抓包文件 `将超时件出库.har` 直连兔喜/ZTO 网关，不再依赖电脑端。

## 抓包文件（事实依据）

HAR 中的请求为：

- URL：`POST https://ztwjgateway.zto.com/gateway.do/`
- `X-Zop-Name: tuxi.spm.stock.outbound`
- `X-Sv-V: com.zto.ztoFamilyNew_4.44.0`、`X-Ca-Version: 1`、`X-App-Version: 4.44.0`
- UA：`wanjiaExpress/4.44.0 (iPhone; iOS 26.1; Scale/3.00)`
- 鉴权头：`x-iam-token`、`X-Userid`、`X-Unionid`、`X-Device-Id`、`X-Ys-Dt`
- 表单：`data=<URL编码JSON>`
- 数据内容：`{"receiveMan":"崔*","lation":"116.236085,39.084864","takeDate":1778588655040,"billCode":"79102936311690","remark":"超时出库"}`
- 成功响应：顶层 `status:true` 且 `result.status:true`，`message:"操作成功"`

## 需求（已确认）

1. 出库请求按 HAR 直连 ZTO 网关，无论"服务器连接"开关是否开启，一律不再走电脑端。
2. `remark` 固定为 `"超时出库"`。
3. `lation` 固定发送 `"116.236085,39.084864"`（用户选择，不做定位）。
4. `takeDate` 取点击时刻的毫秒时间戳。
5. 成功/失败后的 UI 表现（按钮置灰/"出库中..."/"已出库"/失败恢复、Toast、音频、未出库计数）保持不变。

## 实现设计

### DirectApiClient 新增异步方法

`outboundTimeoutPackage(String billCode, String receiveMan, OutboundCallback callback)`

- 新增 `interface OutboundCallback { void onSuccess(JSONObject response); void onError(String error); }`。
- 在后台线程执行：`ensureLogin()` 获取 `accessToken/userId`；构造 `data` JSON（`receiveMan`、`lation` 固定值、`takeDate=System.currentTimeMillis()`、`billCode`、`remark="超时出库"`）；`data=` URL 编码后 POST 到 `https://ztwjgateway.zto.com/gateway.do/`。
- 请求头：按 HAR 固定版本/UA；`x-iam-token`/`X-Userid` 用登录结果；`X-Unionid`/`X-Device-Id`/`X-Ys-Dt` 沿用现有直连默认值。
- 成功判定：顶层 `status==true` 且 `result.status==true`；否则错误信息取 `result.failReason` 或顶层 `message`。
- 错误信息含 token/登录/未授权/过期/无效/令牌等关键词时清空缓存 token，下次自动重登。
- 回调经主线程 Handler 派发，便于直接更新 UI。
- 日志：请求发起与响应走 `LogRecorder`（模块 `DirectApi`）。

### TimeoutFragment 修改

- `doTimeoutOutbound(...)` 改为调用 `directApiClient.outboundTimeoutPackage(billCode, receiveMan, callback)`，删除对 `apiService.outboundPackage` 的调用。
- 回调体内容（成功变暗/按钮"已出库"/音频/Toast/计数；失败恢复按钮与文案/错误音频/Toast）原样保留。
- 入口日志保留（模块 `Timeout`）。

## 错误处理

- 网络异常/响应为空/JSON 解析失败：回调 `onError`，UI 恢复按钮并 Toast"出库失败: 原因"。
- 登录失败：错误信息透传给 UI。
- 业务失败：按 `failReason`/`message` 提示。

## 测试要点

1. 点击"立即超时出库"→ 日志出现 DirectApi 出库记录，请求体 `remark` 为"超时出库"、`lation` 为固定坐标。
2. 成功：卡片变暗、按钮禁用并显示"已出库"、计数减少、Toast"出库成功"。
3. 失败：按钮恢复可用并显示"立即超时出库"、Toast"出库失败: 原因"。
4. 服务器连接开关开/关两种状态下出库均直连 ZTO。
