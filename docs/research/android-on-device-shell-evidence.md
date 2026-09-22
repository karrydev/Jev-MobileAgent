# 手机端独立运行：shell、Shizuku 与 Android CLI 的边界

资料核查日期：2026-09-21；方案更新：2026-09-22。本文分析 App 在手机上获取跨应用界面和执行动作的权限条件。当前项目先自用、从开始公开、不上应用商店；生产通道选用无障碍服务，Shizuku/无线 ADB 仅为需要额外系统能力时的备选。只查询官方文档和一手源码，没有进行手机连接、操作、安装或升级。前轮主机工具证据见 [Android CLI 调研](./android-cli-evidence.md)。

## 结论

**手机端独立自动操作可行，但普通 App 执行 shell 命令并不会获得 ADB 的能力，Google Android CLI 也不是可直接嵌入 APK 的手机控制 SDK。**

可以选择两条不同的设备权限路线：

1. 用户启用 App 的 `AccessibilityService`，通过公开 Android API 读取可访问性节点并执行节点动作/手势。无需电脑、无需 ADB；适合作为普通用户产品的基础执行器。
2. 用户在 Android 11+ 上启用无线调试并完成同机配对，以 Shizuku 或内置 ADB 客户端启动 shell 身份执行进程，再运行 UI Automator/UiAutomation 布局采集与动作执行。无需电脑和 root，但仍有调试设置、配对、重启后重新启动及机型适配成本。

模型的位置与执行器权限是两个问题：本项目先由服务端运行 Python 编排和模型调用，App 负责本机观察与执行；模型输出不会解决操作系统权限。

## 为什么 `Runtime.exec("sh")` 不等于 `adb shell`

AOSP 的应用沙箱以 Linux UID 为基础，约束 Java、原生代码和 OS 命令的执行，不因改用 shell/JNI 而消失。由此可推导：普通 App 启动 `/system/bin/sh`，只是在自身权限上下文中执行另一个程序，不能因为程序名字叫 shell 就变成 shell UID。[AOSP Application Sandbox](https://source.android.com/docs/security/app-sandbox)

ADB 启动的设备端 shell 通常使用 UID 2000。它的特权来自系统为 shell 身份配置的权限、SELinux 上下文等，不来自终端界面或命令语法。Shizuku API 文档明确区分 root UID 0 与 ADB UID 2000，并指出 shell 不能任意访问其他 App 的私有数据。[Shizuku API 权限说明](https://github.com/RikkaApps/Shizuku-API/blob/a27f6e4151ba7b39965ca47edb2bf0aeed7102e5/README.md#differents-of-the-privilege-betweent-adb-and-root)

这不是推测性的权限差异，AOSP 源码提供了具体证据：

- `android.permission.INJECT_EVENTS` 的 protectionLevel 为 `signature`。
- `android.permission.RETRIEVE_WINDOW_CONTENT` 为 `signature|privileged`。
- Shell 系统包声明了上述两个权限。
- `AccessibilityManagerService.registerUiTestAutomationService()` 强制检查 `RETRIEVE_WINDOW_CONTENT`。

来源：[平台权限定义](https://github.com/aosp-mirror/platform_frameworks_base/blob/1cdfff555f4a21f71ccc978290e2e212e2f8b168/core/res/AndroidManifest.xml)、[Shell Manifest](https://github.com/aosp-mirror/platform_frameworks_base/blob/1cdfff555f4a21f71ccc978290e2e212e2f8b168/packages/Shell/AndroidManifest.xml)、[AccessibilityManagerService](https://github.com/aosp-mirror/platform_frameworks_base/blob/1cdfff555f4a21f71ccc978290e2e212e2f8b168/services/accessibility/java/com/android/server/accessibility/AccessibilityManagerService.java)。因此，普通 APK 仅在 Manifest 声明这些权限或 `exec("input tap ...")`，并不能取得跨应用控制资格。

## 手机端获取 shell 身份的路径

| 路径 | 是否可以完全不用电脑 | 普通 App 需要做什么 | 主要边界 |
|---|---|---|---|
| 普通 `Runtime.exec` / `ProcessBuilder` | 可以执行自身权限内命令 | 启动子进程 | UID 未提升，不能据此跨应用注入点击/采集任意 UI |
| Shizuku + 无线 ADB，Android 11+ | 官方支持 | 用户安装/启动 Shizuku、开启调试、完成配对、授权本 App；App 使用 Shizuku API | 非 root 为 shell UID 2000；每次重启需要重新启动服务 |
| App 内置 ADB 协议客户端 | 技术可行，Shizuku 是一手实现证据 | 自行实现配对、TLS、密钥保管、服务发现、连接、shell 服务与重连 | 仍需用户打开无线调试和配对；不是无授权提权 |
| Android 10 及以下非 root | Shizuku 官方标准流程需要电脑启动 | ADB 电脑引导启动 | 不满足“首次也无需电脑”的普遍产品要求 |
| 已 root 设备 | 可直接在设备上授权/启动 | 用户授予 root，运行 root 服务或使用 root 模式 Shizuku | 设备必须预先 root；不是普通安装流程；root 也需考虑 SELinux/系统边界 |
| OEM 系统集成 | 可由厂商交付 | 平台签名、系统镜像/权限配置、适配系统接口 | 需要厂商合作；自己的 APK 签名不等于平台签名 |

Shizuku 的官方无电脑启动流程支持 Android 11+；配对通常只做一次，**重新启动 Shizuku**与**重新配对**是两件事。官方要求每次手机重启后重新执行启动步骤，并提示 OEM 后台限制、调试设置可能导致服务停止。[Shizuku 用户手册](https://shizuku.rikka.app/guide/setup/)

Google 的无线调试官方流程要求启用开发者选项、无线调试和网络授权；配对授权可保留，也可被用户撤销。Android 17/ADB Wi-Fi 2.0 引入的自动连接改善，不能直接推导为现有 Shizuku 在所有设备上自动开机启动。[ADB 官方文档](https://developer.android.com/tools/adb#wireless-android11-command-line)

应将 Wi-Fi/无线调试作为这条引导流程的部署条件，不承诺所有厂商都能在无 Wi-Fi 网络时完成配对，或关闭所有调试选项后服务仍会持续运行。本次没有在目标设备验证这些变体。

## 同机 ADB 与 Shizuku 的明确源码证据

Shizuku 自己在手机内实现 ADB 客户端，不依赖 Mac 上的 Google `android` 命令：

- `AdbPairingService.kt` 在接收配对码时使用 `host = "127.0.0.1"`，创建 `AdbPairingClient`。
- `AdbClient.kt` 实现 Socket/TLS 连接，以 ADB `shell:` 服务发送命令。
- `StarterActivity.kt` 使用该客户端连接并执行 `Starter.internalCommand`，启动服务后关闭客户端连接。

来源：[AdbPairingService](https://github.com/RikkaApps/Shizuku/blob/b844bc491f1790c72328e1a8e5b2349f8978f0ea/manager/src/main/java/moe/shizuku/manager/adb/AdbPairingService.kt)、[AdbClient](https://github.com/RikkaApps/Shizuku/blob/b844bc491f1790c72328e1a8e5b2349f8978f0ea/manager/src/main/java/moe/shizuku/manager/adb/AdbClient.kt)、[StarterActivity](https://github.com/RikkaApps/Shizuku/blob/b844bc491f1790c72328e1a8e5b2349f8978f0ea/manager/src/main/java/moe/shizuku/manager/starter/StarterActivity.kt)。这是“同一台手机可以配对自身 adbd”的实现证据；不能因此假定所有 Android/OEM 版本均已验收。

**App 集成应使用 `UserService`，无需把 rish 当成产品核心依赖。**UserService 是 shell/root 身份的独立 Java/JNI 进程，经 Binder 与普通 App 通信；普通 App 主进程不会被改成 UID 2000。它也不是标准 Android 应用进程，某些 `Context` API 不可用。[Shizuku API UserService](https://github.com/RikkaApps/Shizuku-API/blob/a27f6e4151ba7b39965ca47edb2bf0aeed7102e5/README.md#userservice)

`rish` 面向终端用户，提供带 TTY 的权限 shell；入口通过 `/system/bin/app_process` 加载 `ShizukuShellLoader`，前提仍是已运行且已授权的 Shizuku。它是权限代理客户端，不是无需授权的本地 shell 替代品。[rish 入口源码](https://github.com/RikkaApps/Shizuku/blob/b844bc491f1790c72328e1a8e5b2349f8978f0ea/manager/src/main/assets/rish)

## “已有 shell”之后还需要哪些设备端组件

| 功能 | shell 路线中的实际组件 | 不能混淆的边界 |
|---|---|---|
| 点击、滑动、按键 | 在 shell 身份进程执行系统 `input`，或相应 InputManager/UiAutomation 调用 | 普通 App 身份直接执行同名命令无相同特权 |
| UI 树 | 系统 `uiautomator dump`，或自己部署并启动 UI Automator/UiAutomation runner | Shizuku API 没有直接提供现成的 `android layout` JSON API |
| 语义动作与文本 | UI Automator/UiAutomation 节点或另一路 AccessibilityService | CLI `input text` 不保证任意中文 Unicode；要单独适配 |
| 截图 | shell 截图命令、UiAutomation，或独立的屏幕采集 API | 截图不是 UI 树；受保护画面等边界仍存在 |
| JSON、增量树、节点引用 | 产品自己的树遍历、筛选、规范化、快照映射 | 有 shell 不代表自动拥有 Google CLI 的转换与版本兼容逻辑 |

官方 `UiAutomationConnection` 源码直接说明：shell 向 instrumentation 传入远程连接对象，为后者提供本身不拥有的特权操作，用于跨应用测试和屏幕观察。[UiAutomationConnection](https://github.com/aosp-mirror/platform_frameworks_base/blob/1cdfff555f4a21f71ccc978290e2e212e2f8b168/core/java/android/app/UiAutomationConnection.java)

可选实现是“独立测试/辅助 APK + `am instrument` + UiAutomation”，或“shell UserService 中自建 UiAutomation 连接”。后者涉及非 SDK 接口与系统版本适配，不是 Shizuku 官方开箱即用的 UI 控制功能。系统 `uiautomator dump` 可以做兼容探针，但逐次启动命令的方式是否满足时延目标仍需测量。

## UiAutomation 与无障碍服务会冲突

这点对“Accessibility 基础路线 + shell 增强路线”尤其重要：

1. `UiAutomation` 默认会抑制其他无障碍服务。API 24 起可以传 `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES`，允许已启用服务继续运行及新服务启动。
2. UI Automator 的 `Configurator.setUiAutomationFlags(...)` 可以配置该 flag，应在获得 UiAutomation 会话前设置；直接使用 instrumentation 时对应 `getUiAutomation(flags)`。
3. 不要用 `FLAG_DONT_USE_ACCESSIBILITY` 代替它：后者关闭依赖 accessibility 的能力，不能解决同时还要读 UI 树的问题。
4. AOSP `UiAutomationManager` 只允许同一时刻注册一个使用 accessibility 的 UiAutomation；已有会话时再次注册会抛错。即使允许普通 AccessibilityService 共存，也不代表可以并行开启多个 UI Automator runner。

来源：[UiAutomation flags](https://developer.android.com/reference/android/app/UiAutomation#FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)、[Configurator](https://developer.android.com/reference/androidx/test/uiautomator/Configurator#setUiAutomationFlags(int))、[UiAutomationManager 注册及 suppress 判断](https://github.com/aosp-mirror/platform_frameworks_base/blob/1cdfff555f4a21f71ccc978290e2e212e2f8b168/services/accessibility/java/com/android/server/accessibility/UiAutomationManager.java)。

工程上应由单一设备执行器串行管理会话、动作、快照和释放，明确退出时 disconnect；不要同时让 CLI dump、测试 runner 和自建 UiAutomation 各自抢占注册。原生 shell 命令的默认连接 flag 不应凭空假定可配置，要检查对应 Android 实现或换成可控制 flags 的 runner。

## Google Android CLI 能否直接搬到手机

本机 `android` 是 macOS arm64 Mach-O launcher，配套 Java/JRE bundle、SDK/ADB 依赖；它不能直接在 Android 的原生进程环境执行。官方安装渠道列出 Linux x86_64、macOS arm64/x86_64、Windows，没有 Android APK/Android arm64 发行包。[Google Android CLI 安装文档](https://developer.android.com/agents/skills/devtools/android-cli/skill)

所以准确说法是：**未找到官方支持的直接手机运行方式；移植不是权限方案，也不是产品必须走的路线。**即便自行移植或重写命令行壳，App UID 仍需上文的用户授权机制。更合理的是在 Android App/辅助进程内实现所需的布局读取和动作接口，复用 CLI 的思想与 schema，而不嵌入整套桌面 SDK。

## 系统签名与 root 的补充边界

普通“用自己的 release key 签名”不提供平台 signature 权限。OEM 路线需要与系统平台证书/系统权限配置配合；从 Android 15 开始，平台签名的非系统 App 在非 debuggable 系统上还可能需要平台 signature permission allowlist。[签名权限定义](https://developer.android.com/guide/topics/manifest/permission-element#plevel)、[AOSP signature permission allowlist](https://source.android.com/docs/core/permissions/signature-permission-allowlist)

root 路线可以作为自有测试设备或特定用户的可选部署方式，但不应作为“普通用户安装 App 即能使用”的默认前提。对 shell/root 可用操作仍需按目标系统版本检查权限；不会因为换成 root 或系统应用就自动得到缺失的 Canvas/WebView 语义。

## 对本项目的建议与待验收项

采用统一的设备观察与动作接口，首期实现 AccessibilityService 后端；只有出现确切系统能力缺口时，再评估 Shizuku/UiAutomation。App 自行采集与执行，不依赖 Google 桌面 CLI；服务端模型和任务编排通过设备接口保持独立。

若产品可以接受 Shizuku/无线调试的启用门槛，也可以先做 Android 11+ 的 shell 路线原型，但应验收：首次配对、重启后恢复、Shizuku 死亡/重新授权、OEM 后台限制、UiAutomation 与已有无障碍服务共存、中文输入、UI 树缺失、动作后快照一致性。无障碍与 shell 两条路线都只能得到 App 暴露的语义，视觉回退仍是独立需求。

本次没有设备实测，无法给出上述路线的机型覆盖率、长期存活率、每步耗时或任务成功率。源码快照为实际在线查询的 Shizuku `b844bc491f1790c72328e1a8e5b2349f8978f0ea`、Shizuku-API `a27f6e4151ba7b39965ca47edb2bf0aeed7102e5`、AOSP GitHub mirror `1cdfff555f4a21f71ccc978290e2e212e2f8b168`；这些镜像头提交日期为 2025 年，平台级结论同时对照了当前官方 API 文档，不能视为已审计每个 2026 年设备系统版本。
