# 畅听自动化助手 (`qishui-ad-helper`)

基于 **Kotlin + Android `AccessibilityService` (API 30+ 静默截屏) + Google ML Kit 离线中文 OCR** 构建的独立原生 Android 自动化工具，专为汽水音乐（`com.luna.music`）「看激励视频领限时免费畅听」流程设计。

> **合规声明**：本工程采用「通用状态机引擎 + 本地可编辑规则」分离架构，仅限个人自用与 Android 无障碍技术学习研究，请勿用于商业分发。

---

## 核心特性

1. **双引擎混合视觉识别**：
   - 优先使用低开销 `AccessibilityNodeInfo` 节点树匹配；
   - 遇到穿山甲 Lynx / `FlattenUIText` 自绘视图节点失效时，自动调用 Android 11+ `AccessibilityService.takeScreenshot()` 静默截屏（无需每次弹窗确认 `MediaProjection` 录屏权限），交由 **Google ML Kit 本地离线中文 OCR** 提取文字与精确屏幕坐标框。
2. **系统级媒体音量守护 (`VolumeGuard`)**：
   - 任务启动自动备份当前系统媒体音量（持久化至本地防异常丢失）并一键静音；任务完成、手动急停或超时中止时自动恢复原音量。
3. **防摇一摇外跳与应用内直播间拉回**：
   - 实时监测前台窗口包名与顶层 `Activity`：若误跳淘宝/浏览器等外部应用，或偏离进入直播间（`LunaDefaultLivePlayerActivity`）/落地页，自动触发全局返回或重拉主任务回前台。
4. **二段激励连看与精准防误触**：
   - 自动识别「再看一个 / 继续观看」续看弹窗，支持连看 2～3 轮拿满全天畅听；
   - 短词（「免 / 免费听」）严格限制顶部导航热区，避免误点歌名或歌词；奖励完成词严格排除「XX秒后可领取」倒计时干扰。
5. **拟人化防风控 (`Humanizer`)**：
   - 中心聚集随机坐标偏移（`±8~15px`）、识别后随机反应延迟（`1.2s~3.1s`）、随机按压时长（`65ms~135ms`）及微滑动轨迹、随机轮询间隔抖动（`2.4s~3.6s`）。
6. **可控悬浮胶囊 + 前台服务 + 热更新规则表单**：
   - 屏幕侧边可拖动迷你状态胶囊（截图瞬间自动隐身避免遮挡与自识别），支持一键紧急停止；
   - App 首页支持直接修改并保存匹配关键词，应用改文案无需重新编译 APK。

---

## 目录结构

```text
qishui-ad-helper/
├── .github/workflows/build-apk.yml   # GitHub Actions 云端自动打包 Debug APK 工作流
├── docs/research-and-mechanism.md    # 业务机制调研与技术方案归档文档
├── gradle/                           # Gradle 8.9 与依赖版本目录 (libs.versions.toml)
└── app/src/main/
    ├── AndroidManifest.xml           # 权限、包可见性 queries、无障碍与前台服务声明
    ├── res/xml/accessibility_service_config.xml
    ├── res/layout/                   # Material 3 控制台页面与悬浮胶囊布局
    └── java/io/github/jiaqi/qishuihelper/
        ├── config/RuleConfigRepository.kt       # 规则与参数持久化
        ├── model/TaskModels.kt                  # 状态机阶段与数据模型
        ├── ocr/ScreenOcrHelper.kt               # 静默截屏 + ML Kit 中文 OCR + 胶囊子框切分
        ├── service/AdHelperAccessibilityService.kt # 无障碍核心宿主与拟人手势派发
        ├── service/TaskStateMachine.kt          # 全流程轮询状态机
        ├── service/KeepAliveForegroundService.kt # 前台通知保活与通知栏急停
        ├── ui/FloatingOverlayManager.kt         # 可拖动悬浮胶囊管理
        ├── ui/MainActivity.kt                   # 权限自检、参数编辑、实时日志控制台
        └── util/                                # 拟人化算法、音量守护、实时日志总线
```

---

## 如何编译生成 APK（两种方式任选）

支持 **Android 11（API 30）及以上** 安卓真机。

### 方式一：使用 Android Studio 本地打开并真机运行（推荐调试）
1. 在 Mac 上下载安装 [Android Studio](https://developer.android.com/studio)（内置 JDK 17 与 Android SDK，无需单独配置 Java）。
2. 打开 Android Studio $\rightarrow$ 点击 **Open** $\rightarrow$ 选择本文件夹 `/Users/lucas/Projects/Jiaqi/qishui-ad-helper`。
3. 等待底部 Gradle Sync 自动下载依赖完成（首次约 2～5 分钟）。
4. 手机通过数据线连接电脑并开启 USB 调试，点击顶部绿色 **Run `app`** 按钮即可直接安装到手机。

### 方式二：免安装本地开发环境，用 GitHub Actions 云端打包 APK
如果你不想在 Mac 上安装体积较大的 Android Studio：
1. 在 GitHub 新建一个 **Private（私有）仓库**；
2. 在本目录下执行 `git init` 并推送到该私有仓库的 `main` 分支；
3. 进入 GitHub 仓库的 **Actions** 页面，等待 `Build Android Debug APK` 运行完成（约 3 分钟），直接下载产物 `qishui-ad-helper-debug-apk` 中的 `app-debug.apk` 发送到手机安装即可。

---

## 安卓手机端首次使用必做配置

1. **解除 Android 13/14/15「受限设置」并开启无障碍**：
   - 打开本 App，点击第 1 栏无障碍右侧「去开启」；
   - 若系统提示「受限设置」开关呈灰色：先点一下灰色条目触发系统提示 $\rightarrow$ 返回本 App 点击「受限设置开启说明 -> 打开本应用详情页」 $\rightarrow$ 点击右上角 `⋮` 选择 **「允许受限设置」** $\rightarrow$ 再次进入无障碍列表即可开启 **「畅听自动化无障碍引擎」**。
2. **开启模拟点击底层权限（小米/澎湃/vivo/OPPO 必做）**：
   - 在系统「开发者选项」中，确保同时打开 **「USB 调试（安全设置）」**（OPPO/一加名为「禁止权限监控」），否则系统会拦截手势点击。
3. **源头封堵汽水音乐摇一摇广告跳转**：
   - 长按桌面「汽水音乐」图标 $\rightarrow$ 应用信息 $\rightarrow$ 权限管理：将 **「获取设备动作与方向」** 与 **「打开其他应用 / 后台弹出界面」** 设为**拒绝**。
4. **开启悬浮窗与省电白名单**：
   - 在本 App 首页依次点亮「悬浮窗胶囊」与「后台省电白名单」，然后点击 **「一键启动自动领畅听」** 即可全自动运行。
