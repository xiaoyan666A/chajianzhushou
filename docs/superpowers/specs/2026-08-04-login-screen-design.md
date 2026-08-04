# 登录界面与凭据/token 缓存设计

日期：2026-08-04
状态：已获用户确认（“确认”）

## 目标

1. 新安装 App 打开后先进入登录界面（兔喜账号 = 手机号 + 密码）；
2. 登录成功后才进入主界面（查件/超时/日志/设置）；
3. 登录后保存账号密码，token 过期时自动用保存的凭据重新登录（保持现状的自动重登行为）；
4. 同时缓存 token，进程重启后优先使用缓存 token，不每次启动都重新登录。

## 现状

- `DirectApiClient.doLogin()` 使用写死的 RSA 密文（`LOGIN_BODY`）登录，token 仅存内存 24 小时；
- 设置页已有“重新登录”按钮（反射调用 `ensureLogin`），并把 token 写入 `local_access_token / local_user_id / local_token_expires`；
- 登录加密已验证可复刻：`authorization = RSA(手机号 + " " + 密码)`，1024 位 / PKCS#1 v1.5 / Base64，公钥来自网页端 JS；
- 主题约定：深色 = `values/` + `drawable/`，浅色 = `values-night/` + `drawable-night/`；MainActivity 的 `configChanges` 不得含 `uiMode`。

## 方案（已确认：方案 A）

新增独立 `LoginActivity` 作为启动入口；`MainActivity` 保留主界面职责并增加“未保存凭据则跳登录页”的守卫。

## 组件

- `LoginStore`：集中管理凭据与 token 缓存的 SharedPreferences 读写（复用 `chajianzhushou_prefs`）。
  - 凭据：`login_username`、`login_password`（明文，用户已确认；`allowBackup=false` 兜底）；
  - token 缓存：沿用 `local_access_token / local_user_id / local_token_expires`，新增 `local_refresh_token / local_union_id / local_ys_dt`。
- `LoginActivity`：登录界面（手机号 + 密码 + 可见切换 + 登录按钮 + 错误提示）；启动时若已有凭据则直接进主界面。
- `DirectApiClient` 改造：
  - `login(Context, username, password)` 公开静态方法：RSA 加密 → POST 网关 → 成功后保存凭据与 token；
  - `doLogin()` 改为使用 `LoginStore` 中的凭据动态生成请求体；
  - `ensureLogin()` 读取顺序：内存 token → 缓存 token（未过期）→ 自动重新登录；
  - 查询检测到 token 过期时同时清空缓存 token，下次自动重登。
- `MainActivity`：
  - 增加 `setAppContext(Context)` 与公开 `applyFontScale`（供 LoginActivity 复用字号与 Context）；
  - onCreate 守卫：无保存凭据 → 跳转 LoginActivity 并结束。
- `SettingsFragment`：
  - “重新登录”改为走 `LoginStore` 凭据 + 新公开登录方法（不再反射）；
  - 新增“退出登录”按钮：确认后清除凭据与 token，跳回登录界面；
  - 账号信息卡片在直连模式下显示登录手机号与登录状态。
- `AndroidManifest.xml`：`LoginActivity` 成为 LAUNCHER；`MainActivity` 移除 launcher 过滤器、`exported=false`；两者 `configChanges` 均不含 `uiMode`。

## 数据流

1. 冷启动 → `LoginActivity` → 有凭据？是：跳主界面（查询时 `ensureLogin` 自动用缓存 token 或自动重登）；否：显示登录界面。
2. 登录提交 → `DirectApiClient.login()` → RSA 加密 → POST `kdcs-wx-lt.zt-express.com/gateway.do/`（`X-Zop-Name: tuxi.spm.account.accountLoginByPwd`）→ 成功保存凭据 + token → 进主界面。
3. token 过期 → 查询报“登录过期/未授权” → 清内存与缓存 token → 下一次 `ensureLogin` 用保存的凭据自动重新登录。
4. 退出登录 → 确认弹窗 → 清凭据与 token → 回登录界面。

## 错误处理

- 手机号/密码为空：界面提示，不发请求；
- 服务器返回“手机号或密码错误”（610）等：界面展示服务器原文；
- 网络异常：展示错误信息；
- 风控（ST005/ST007/ST008）：提示需要验证码，当前无法自动处理，保持登录失败状态；
- 日志：登录成功/失败记 `LogRecorder`，不记录密码明文。

## 兼容

- 老版本升级后无保存凭据 → 首次需手动登录一次（用户已确认）；
- 设置页旧的 `local_access_token` 等 key 继续被 `LoginStore` 复用。
