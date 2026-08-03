# 查件助手 · 手机端（Android）项目规则

本文件是 Codex 的项目规则（AGENTS.md）。开始任何任务前先阅读本规则，再浏览相关源码。

## 项目概述
查件助手是一款快递站点管理应用（手机端），原生 Java + AndroidX 开发，包名 `com.chajianzhushou.app`，minSdk 24 / targetSdk 34。

页面（Fragment 保活，切换只 show/hide）：查件 `QueryFragment` / 超时件 `TimeoutFragment` / 日志 `LogsFragment` / 设置 `SettingsFragment`，由 `MainActivity.switchPage()` 统一调度。

## 环境与命令
- 项目根目录：`C:\AndroidBuild`
- Git 路径：`C:\Program Files\Git\cmd\git.exe`（终端可能无法直接识别 `git`，必要时用完整路径）
- 构建 APK：在 `C:\AndroidBuild` 运行 `.\gradlew.bat assembleDebug`，产物 `app\build\outputs\apk\debug\app-debug.apk`
- 构建日志写入 `build_apk.log` / `build_err.txt`（已 gitignore）

## Git 规则
- 仓库：`C:\AndroidBuild`，分支 master，仅本地管理，未关联远程
- 机器未配置全局 git user.name / user.email，提交时必须用临时参数：
  `git -c user.name="rrzu" -c user.email="rrzu@local" commit -m "中文提交说明"`
- **禁止自动提交**：除非用户明确要求，否则只改代码不提交；提交前先 `git status` 核对文件
- 不要 `git add .` 整包提交，只暂存本次改动的文件
- 已忽略：`build/`、`.gradle/`、`local.properties`、`.idea/`、`*.iml`、`build_apk.log`、`build_err.txt`

## 主题系统（最重要，勿违反）
- **深色模式 = `res/values/`、`res/drawable/`（默认资源）；浅色模式 = `res/values-night/`、`res/drawable-night/`**。代码用 `AppCompatDelegate.setDefaultNightMode(MODE_NIGHT_YES/NO)` 强制切到夜间模式以加载白天（浅色）资源，因此浅色 UI 必须放在 `-night` 资源里。
- `AndroidManifest.xml` 中 `MainActivity` 的 `configChanges` **绝不能声明 `uiMode`**，否则 `setDefaultNightMode` 不触发 Activity 重建，主题切换不生效。
- 主题切换逻辑在 `ThemeManager.java`，自动模式按日出日落（缓存定位）每 60 秒重算。
- 新增颜色/背景请同时提供 `values/` 与 `values-night/`、`drawable/` 与 `drawable-night/` 两套，并保证 WCAG 对比度（浅色卡接近纯白 + 深色文字；深色卡提亮 + 浅色文字）。

## 功能约定
- **管理员密码访问控制（已实现，勿回退）**：点击设置按钮先验证密码，`AdminGate.java` 负责 SHA-256 哈希校验与 30 分钟会话（SharedPreferences）。默认密码 `888888`。设置页可改密码。
- **日志**：统一用 `LogRecorder.info(ctx, 模块, 标题, 内容)`，模块例如 `SETTINGS`（设置变更）、`IMAGE`（图片加载）、`HTTP_OUT`（HTTP 请求）、`Main`。
- **图片**：`ImageLoader.java` 按 URL 做 key 缓存；URL 变化自动失效重载。查件卡片图片加圆角用 `setClipToOutline(true)`。
- **自动刷新指示器**：查件页右上角"自动刷新中......"文字 + 圆环，仅当"自动刷新间隔"开启时显示；自动刷新执行时变绿转动，空闲变暗；刷新前 1 秒提前变绿；自动刷新进行中禁用"竖向排列"开关（`QueryFragment.setAutoRefreshIndicatorActive` 统一管理）。
- **查件网格**：卡片按行对齐（行 `FILL_HORIZONTAL` + 卡片 `MATCH_PARENT` + info 区 `weight=1` 贴底）；信息行强制单行省略号；不要用负 margin 让卡片贴边（会导致屏幕外内容被裁），改用页面 padding 控制。
- **下拉框**：一律用自定义布局 `spinner_item.xml`（已选态）与 `spinner_dropdown_item.xml`（下拉项），显式 `textColor=@color/ink`，禁用系统 `simple_spinner_item`（与本项目反色资源约定冲突会白字/黑字看不清）。
- 界面文案、代码注释一律中文。

## 工作流程
1. 先读相关文件（MainActivity / 对应 Fragment / AdminGate / 相关 layout 与 colors），理解现状再动手
2. 改动最小化，不引入与需求无关的重构
3. 做其它额外的改动时先向我报告，我同意后你再执行
4. 完成后必须运行 `.\gradlew.bat assembleDebug` 验证编译通过
5. 构建成功后再向用户汇报产物路径；用户要求时才提交 git
