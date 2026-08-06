# 查件助手 · UI 组件清单

> 本文档盘点 App 内所有界面与 UI 组件，作为开发、改版、统一风格的参考。
> 生成日期：2026-08-06（对应提交 bac0865）

## 1. 页面总览

| 页面 | 载体 | 布局 | 说明 |
| --- | --- | --- | --- |
| 查件 | `QueryFragment` | `fragment_query.xml` | 主页面：查询、自动刷新、包裹卡片列表 |
| 超时件 | `TimeoutFragment` | `fragment_timeout.xml` | 超时件列表与"立即超时出库" |
| 日志 | `LogsFragment` | `fragment_logs.xml` | LogRecorder 日志查看/复制 |
| 设置 | `SettingsFragment` | `fragment_settings.xml` | 账号、主题、刷新、语音等全部设置 |
| 登录 | `LoginActivity` | `activity_login.xml` | 首次安装/退出后的登录页 |
| 主框架 | `MainActivity` | `activity_main.xml` | 底部导航 + Fragment 保活（show/hide 切换） |

> 导航：`MainActivity.switchPage()` 统一调度；底部导航 `bottom_nav_menu.xml`（查件 / 超时件 / 设置）。

## 2. 自定义 View（Java 自绘组件）

| 组件 | 类 | 用途 |
| --- | --- | --- |
| 流水灯边框 | `FlowBorderView` | 黄色光点沿圆角矩形持续环绕（跑马灯），用于超时件"出库时间"外圈标注 |
| 颗粒渐隐 | `DissolveView` | 位图逐帧随机抹除的颗粒化消失特效，用于刚出库卡片消失动画 |

## 3. 弹窗 / Dialog

| 弹窗 | 类 | 内容 |
| --- | --- | --- |
| 图片预览 | `ImagePreviewDialog` | 全屏看图：Matrix 缩放/平移、上一张/下一张、入库照 ↔ 出库照切换 |
| 轨迹详情 | `TrajectoryDialog` | 长按包裹卡片弹出：按 HAR 解析的轨迹环节、各环节操作人、状态、入库/出库图片 |
| 通用对话框 | 系统 `AlertDialog` | 确认/提示（如超时出库确认、检测更新），统一套用 `Theme.Chajianzhushou.Dialog` 主题 |
| 下载进度 | `ProgressDialog` | 更新 APK 下载进度（横向进度条 + 百分比） |

## 4. 核心业务组件（代码构建）

| 组件 | 类 | 职责 |
| --- | --- | --- |
| 包裹卡片生成器 | `PackageCardFactory` | 卡片全部 UI 代码化构建：横/竖排列、状态、图片、取件码、单号、语音、超时三层标注 |
| 查询历史面板 | `QueryHistoryPanel` | "最近查询"记录渲染、横向标签、点击回填、长按删除、右上角清空 |
| 自动刷新控制器 | `AutoRefreshController` | 刷新计时、次数上限、暂停/恢复（配合顶部指示器） |
| 图片加载器 | `ImageLoader` | OkHttp 下载 + 内存缓存 + 解码，卡片链路 1080 上限、预览链路 2048 |
| URL 解析器 | `ImageUrlResolver` | 直连模式原始图片路径 → 签名 URL 的会话级解析与缓存 |
| 磁盘缓存 | `ImageCacheManager` | 图片按单号+URL 指纹落盘、过期策略、清理 |
| 更新检查 | `UpdateChecker` | GitHub Releases 检测、强制更新、断点续传、签名校验、安装 |
| 语音播报 | `TtsHelper` / `AudioPlayerHelper` | MiMo TTS 播报与内置 WAV 播放 |
| 主题管理 | `ThemeManager` | 深/浅/自动三模式，日出日落每 60 秒重算 |
| 错误提示 | `UiErrorHandler` | 统一网络/查询错误文案（如"网络错误"） |

## 5. 布局文件清单

`app/src/main/res/layout/`：

| 布局 | 用途 |
| --- | --- |
| `activity_login.xml` | 登录页（账号/密码/验证码登录、倒计时） |
| `activity_main.xml` | 主框架 + 底部导航 |
| `fragment_query.xml` | 查件页完整结构 |
| `fragment_settings.xml` | 设置页全部功能卡片 |
| `fragment_logs.xml` | 日志列表页 |
| `fragment_timeout.xml` | 超时件页 |
| `item_timeout_card.xml` | 超时件列表项卡片 |
| `spinner_item.xml` / `spinner_dropdown_item.xml` | 下拉框已选态/下拉项（自定义，禁用系统主题） |

## 6. 查件页组件分解（fragment_query.xml）

从上到下：

| 区域 | 关键 id | 说明 |
| --- | --- | --- |
| 顶部栏 | `auto_refresh_label` / `auto_refresh_indicator` | 标题"查件" + 自动刷新文字/圆环（空闲暗、执行时绿转） |
| 搜索类型 | `btn_type_phone` / `btn_type_pickup` / `btn_type_bill` | 手机尾号 / 取件码 / 运单号 三个 pill 按钮 |
| 搜索框 | `tv_search_icon` / `et_bill_code` / `btn_clear` / `btn_voice` / `btn_query` | 放大镜 / 输入框 / 清除 / 语音 / 查询 |
| 连续输入标签流 | `tail_search_wrap` / `tail_tags_container` | 多尾号标签 + 尾部输入框（设置开关开启后显示） |
| 筛选条 | `result_count_box` / `tv_result_count` / `result_count_marquee` / `result_count_flow` / `switch_grid_view` / `switch_show_delivered` | "x 个包裹 · 待取 x · 超时出库 x"（超时文字带跑马灯）+ 竖向排列 + 显示已出库 |
| 列表 | `results_container` | 包裹卡片动态添加容器 |
| 空态 | `tv_no_results` | 无结果提示 |
| 加载态 | `progress_bar` | 转圈加载 |
| 历史面板 | `history_panel` | 悬浮查询记录（覆盖列表、半透明、不挤压布局） |
| 全屏遮罩 | — | 查询执行中的全屏 Loading |

## 7. 包裹卡片结构（PackageCardFactory）

- 卡片底：`CardView`（圆角 18dp）+ 三种背景 `bg_pkg_card`（已出库）/ `bg_pkg_card_pending`（待取件，绿框）/ `bg_pkg_card_timeout`（超时件，黄框闪烁）
- 图片区：`ImageView`（圆角、`FIT_CENTER`、点击进预览）
- 信息区（weight=1 贴底）：
  - 状态标签：待取件（绿）/ 已出库（灰）/ 超时出库 x天（黄，跑马灯）
  - 单号（点击复制）、收件人、入库/出库时间
  - 取件码（绿色大号、点击复制）
  - 语音播报按钮、拍照出库按钮（待取件卡片，设置开关控制）
- 已出库普通卡片整体轻微调暗（`setAlpha(0.8f)`）

## 8. 设置页功能卡片（fragment_settings.xml）

| 卡片 | 内容 |
| --- | --- |
| 账号信息 | 门店名称 / 姓名 / 岗位 / 账号 / 门店编码 + 重新登录、退出登录 |
| 界面风格 | 总开关 + 浅色 / 深色 / 自动（AppCompatRadioButton + 主题预览点） |
| 进阶功能 | 总开关（开启后显示语音识别配置、TTS、日志输出、服务器连接） |
| 超时件标注 | 显示超时件标注、标注最近 N 天（1~20，默认 3） |
| 界面显示 | 界面字号选项 |
| 缓存管理 | 清理图片缓存 / 日志、缓存保留天数 |
| 输入框连续输入 | 多尾号连续输入（进阶功能控制） |
| 语音识别配置 | 语音识别开关、Mimo Key |
| 自动刷新 | 刷新间隔、次数上限（3~30） |
| Mimo API Key | 独立卡片（与语音识别配置分开） |
| TTS 语音播报 | 播报开关、音色、风格、音量跟随 |
| 日志输出 | 日志总开关 |
| 服务器连接 | 服务器模式开关 |
| 版本信息 | 页面最底部单行文字（版本号） |

## 9. 控件模式

| 控件 | 实现 | 备注 |
| --- | --- | --- |
| 按钮 | `Button` + `bg_btn_accent` / `bg_btn_danger` / `bg_btn_warning` / `bg_btn_back` | 高度 42/48/52dp |
| 开关 | `androidx.appcompat.widget.SwitchCompat` + `switch_thumb` / `switch_track` | 高度 28dp |
| 单选框 | `AppCompatRadioButton`，`buttonTint=@color/accent` | 主题模式选择 |
| 下拉框 | `Spinner` + 自定义 item 布局 | 禁用系统主题（白字/黑字看不清） |
| FAB | `Button` 圆角 + `bg_float_accent` / `bg_float_warning` | 语音识别等快捷入口 |
| Toast | 系统 Toast + `bg_toast` 自定义背景 | 复制成功、查询提示等 |
| 状态标签 chip | `TextView` + `bg_status_pending` / `bg_status_delivered` / `bg_status_timeout` | 待取件绿 / 已出库 / 超时黄 |
| 取件码 | 大号绿色等宽文字，点击复制 | 例外字号 20sp |
| 输入框 | `EditText` + `bg_input` | 高度 48dp |
| 历史标签 | `HorizontalScrollView` 内横向标签（带圆角描边） | 点击切换/回填、长按删除 |

## 10. 资源体系

### 颜色（双套：深色=values/，浅色=values-night/）

与电脑端 CSS 变量严格对齐。主色板：

| 变量 | 深色 | 浅色 | 用途 |
| --- | --- | --- | --- |
| `bg` | `#08090B` | `#EEF2F6` | 页面背景 |
| `card_solid` | `#171A1F` | `#F2F5F9` | 卡片底色 |
| `ink` / `ink2` / `muted` | 浅灰系 | 深灰系 | 三级文字 |
| `accent` | `#00F5D4`（青绿） | `#00A98F` | 主色/强调 |
| `danger` | `#FF5364` | `#D9384B` | 危险/错误 |
| `warning` | `#FFA726` | `#D98A16` | 超时/警告 |
| `success` | `#81C784` | `#3E9B4F` | 待取件绿 |
| `champagne` | `#F6D69A` | `#8A6D2F` | 单号等点缀 |

另有一系列半透明变体（`accent_xx` / `warning_xx` / `danger_xx` / `success_xx` / `white_xx`），用于描边、水印、遮罩。

### 尺寸（values/dimens.xml）

分类齐全：圆角（10~22dp）、字号（13~38sp）、页面间距、卡片、按钮、FAB、搜索框、开关、超时卡片、空态、进度条、输入框、头像、分隔线、微间距、日志卡片、状态标签、网格模式。

### 背景 drawable

- 卡片：`bg_pkg_card`（三种状态）、`bg_settings_card`、`bg_card`、`bg_timeout_card`
- 按钮：`bg_btn_accent` / `bg_btn_danger` / `bg_btn_warning` / `bg_btn_back` / `bg_float_*`
- 输入：`bg_input` / `bg_search_box` / `bg_search_type_btn`
- 状态：`bg_status_pending` / `bg_status_delivered` / `bg_status_timeout`
- 图片：`bg_pkg_image` / `bg_image_placeholder`
- 主题：`bg_theme_*`（模式选项、预览点、自动模式标记）
- 其它：`bg_header_countdown`、`bg_history_overlay`、`bg_auto_refresh_ring`、`bg_toast`、`bg_timeout_count`、`switch_thumb` / `switch_track`

> 注意：凡是"浅色模式需要用到的"，必须同时在 `drawable-night/` 提供一套（项目反色约定）。

### 主题

- `Theme.Chajianzhushou`（MaterialComponents DayNight NoActionBar）：全局主色、状态栏、导航栏、对话框主题
- `Theme.Chajianzhushou.Dialog`：统一所有 AlertDialog 视觉

### 其它资源

- `ids.xml`：`image_loader_tag`、`tag_pkg_pending`、`tag_pkg_status`、`tag_pkg_rawpath`
- `raw/`：内置语音（没有待取件、没有待取件但有疑似超时、服务器错误、超时出库成功/部分失败 等 wav）
- `xml/`：`file_paths.xml`（FileProvider）、`network_security_config.xml`、`backup_rules.xml`、`data_extraction_rules.xml`
- `mipmap-*`：应用图标

## 11. 主题与字体约定（重要）

- **深色模式 = `values/` + `drawable/`；浅色模式 = `values-night/` + `drawable-night/`**。代码用 `AppCompatDelegate.setDefaultNightMode` 强制切换。
- `AndroidManifest.xml` 中 `MainActivity` 的 `configChanges` **绝不能包含 `uiMode`**，否则切换主题不重建界面。
- 全局字号已统一增大；例外：结果数框 16sp、"超时出库 x天"14sp、取件码绿色值 20sp。
- 下拉框禁用系统 `simple_spinner_item`，一律用 `spinner_item.xml` / `spinner_dropdown_item.xml`。

## 12. 可复用/值得关注的点

- 包裹卡片样式集中在 `PackageCardFactory`，改卡片先看这里，不要在各 Fragment 里散改。
- 弹窗统一用 `Theme.Chajianzhushou.Dialog`，新增弹窗不要单独自定义主题。
- 图片加载统一走 `ImageLoader`；预览走 `loadFull`（整图），卡片走 `load`（1080 上限）。
- 新增"浅色模式可见"的颜色/背景时，必须同时补 `values-night/` 与 `drawable-night/`。
