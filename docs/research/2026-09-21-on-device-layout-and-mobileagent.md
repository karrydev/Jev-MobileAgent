# 手机 App 自行获取布局，以及 MobileAgent + Jev 改造方案

> 2026-09-25 运行边界修订：产品以 [ADR-0003](../adr/0003-standalone-android-runtime.md) 和[当前路线](../development-roadmap.md)为准。原版框架、外部工具与核查时的事实保留；旧桥接阶段不代表独立 App 已交付。

资料核查日期：2026-09-21；方案更新：2026-09-22。手机 App 自行采集其它 App 的界面并执行操作；2026-09-25 起要求编排也在 App 内，直连模型，使用时无需电脑或项目服务器。项目先自用、从开始公开、不上应用商店；基于 v3.5 公开 Fork 后裁剪，详见[当前决策](../project-direction.md)。该句原记录的是研究时状态；目前已完成旧桥接式真机闭环，独立 App 和 Jev 实测进度以任务台账为准。

## 判断

**手机端直接实现 AccessibilityService 最贴近目标；Shizuku + UiAutomation 是有条件的补充。MobileAgent 可以作为编排底座，复用规划、执行反馈和记忆；仍需新增手机设备适配层和 Jev 决策层。**

“不依赖电脑”与“全部控制逻辑运行在 APK 内”是两个要求。App 通过互联网连接服务端 MobileAgent，也可脱离电脑运行，且更容易复用 Python 代码。用户现已明确控制循环必须在手机内；当前选择将必要编排行为移植到现有 Android 工程，处理其生命周期，不以 APK 内 Python HTTP 桥交付。

## 布局获取方式对照

| 路线 | 获取的信息 | 普通 App 的前置条件 | 对本方案的定位 |
|---|---|---|---|
| AccessibilityService | 其它 App 暴露的语义树、节点动作、窗口和事件 | 用户在系统设置显式启用服务，声明需要的能力 | 首选设备观察与执行通道 |
| 普通 App 执行 `sh` | 仅原 App 身份可访问的内容 | 无额外权限 | 无法仅靠 shell 获得跨 App 树采集权限 |
| Shizuku / 同机无线 ADB + UiAutomation | 通过测试自动化通道获得可访问性层级及输入能力 | 调试启用、配对、授权；或 root | 开发者/自用模式的备选；不必搬入完整 Android CLI |
| MediaProjection + OCR/VLM | 屏幕像素，以及模型推断的文字/位置 | 屏幕捕获会话授权和相应前台服务 | 视觉后备；不是真实 UI 树，也不提供点击权限 |
| 默认助手 Assist API | AssistStructure、上下文和可选截图 | 用户选为默认助手，符合助理会话生命周期 | 按次获取上下文；不自动获得任意节点点击权限 |
| 自有 App SDK / 可调试 WebView / Layout Inspector | 自有 View、Compose 或 DOM 的内部信息 | 目标应用配合、调试或显式接口 | 适合可控目标，不是读取任意第三方 App 的通用方案 |
| OEM 系统接口 | 特权采集/操作能力，具体依系统接口 | 厂商预装/系统特权及授权 | OEM 合作路线，普通 APK 不能假定可用 |

这几类技术的“树”不等价：Accessibility/UIAutomator 是应用暴露给系统的可访问性语义表示；Layout Inspector 接近开发者的内部结构；OCR/VLM 是从像素重建的推断。换成 shell 路线，通常不会让没有暴露语义的 Canvas 元素凭空出现。

## 无障碍服务的实现边界

可以在 APK 内使用 `rootInActiveWindow`、`getWindows()` 遍历 `AccessibilityNodeInfo`，规范化成 JSON；保留文字、contentDescription、resource ID、角色/类、bounds、交互状态和父子关系。用户启用服务后，不要求电脑常驻或 root。[AccessibilityService](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService)

优先通过节点 `performAction` 执行点击、设置文本和滚动；节点动作不足时，使用 `dispatchGesture` 做坐标手势。API 30 起可按能力声明使用无障碍截图 API，API 34 起有窗口截图。这意味着使用无障碍路线时，不一定还要引入 MediaProjection。[AccessibilityService](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService)、[AccessibilityNodeInfo](https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo)

设计上建议事件触发采集，加合并/去抖与操作后重新采集。节点引用只属于相应观测，异步模型返回时重新核对目标。节点动作返回成功不能代替结果检查；语义树可能缺失、合并或在动画期间变化。WebView、Compose 和自绘内容的覆盖由目标应用的实现决定。[Compose semantics](https://developer.android.com/develop/ui/compose/accessibility/semantics)

## App 内 shell 与 Android CLI 要分开看

普通 App 使用 `Runtime.exec`/`ProcessBuilder` 启动 shell，仍在自己的 UID/SELinux 约束内；安装终端、嵌入 Python 或将二进制复制进 APK，不会自动得到 `adb shell` 的权限。[Android 应用沙箱](https://source.android.com/docs/security/app-sandbox)

Google Android CLI 当前是主机工具。本机已核查版本的 `android layout` 内部使用 `uiautomator dump` 并将 XML 转 JSON；这说明它封装了已有设备能力，不是提供一种普通 APK 可直接调用的新权限。移植完整 CLI 还涉及平台二进制和运行依赖，对只取树的需求没有必要。[CLI 官方入口](https://developer.android.com/tools/agents/android-cli)、[上轮本机检查](android-cli-evidence.md)

获得用户授权的 shell 身份后，可由 Shizuku UserService 或同机 ADB 启动专用 UIAutomator/UiAutomation runner，再通过 Binder/socket 把树返回 App。Shizuku 是权限与进程桥，不直接等于布局采集器。Android 11+ 支持无线调试路径，但仍有初始配对、重启后重新启动、OEM 限制等使用条件。[Shizuku 用户手册](https://shizuku.rikka.app/guide/setup/)、[Shizuku API](https://github.com/RikkaApps/Shizuku-API)

如果并用 AccessibilityService 与 UiAutomation，应处理后者默认抑制其它无障碍服务的行为，按适用路径配置 `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES`。不要多个 runner 同时争用自动化连接。[UiAutomation](https://developer.android.com/reference/android/app/UiAutomation)

## 其它路线的具体价值

**MediaProjection。** 它提供显示内容的媒体流，可以取帧交给 OCR/VLM；没有节点 ID、点击 action 或父子关系，也不会授权跨 App 注入点击。每个捕获会话需要同意，不是每一帧都需要弹窗；Android 14+ 对 token 复用和前台服务有明确要求。已经使用 AccessibilityService 时，可以优先评估其截图接口。[Media projection](https://developer.android.com/media/grow/media-projection)

**默认助手。** 用户选定助手后，`VoiceInteractionSession.onHandleAssist` 可以接收 AssistStructure，`onHandleScreenshot` 可接收截图。该数据与助理会话关联，可能因用户/设备策略或安全窗口而缺失；不能理解为常驻自由读取、任意点击所有 App 的接口。`requestDirectActions` 只能取得目标应用主动提供的 actions。[Assistant 指南](https://developer.android.com/training/articles/assistant)、[VoiceInteractionSession](https://developer.android.com/reference/android/service/voice/VoiceInteractionSession)

**Autofill。** 表单触发时确实可获得 AssistStructure，但职责是提供填充建议，官方限定数据用途；它不适合作为通用 Agent 抓取界面树的替代通道。[Autofill 官方指南](https://developer.android.com/identity/autofill/autofill-services)

**目标应用配合。** 自有 App 可以直接暴露业务状态/操作 API；可调试 WebView 可以暴露 DOM，但必须由目标应用开启调试。Layout Inspector 针对 debuggable 进程，不能用于任意生产第三方 App。[WebView 调试](https://developer.chrome.com/docs/devtools/remote-debugging/webviews/)、[Layout Inspector](https://developer.android.com/studio/debug/layout-inspector)

**Android 新的 Agent 接口。** AppFunctions 在 Android 16+ 可由应用显式提供工具，但当前仍是实验预览，完整调用链对参与应用/系统 Agent 有限制。Computer Control 当前面向 OEM 预装助手，需要特权 `ACCESS_COMPUTER_CONTROL`，并非普通 APK 的通用入口。这两类值得关注，但不能作为当前任意第三方 App 自动化的无条件依赖。[AppFunctions](https://developer.android.com/ai/appfunctions)、[Computer Control](https://developer.android.com/ai/computer-control)

## 已有设备端项目可以减少从零实现

已核实 [`droidrun/droidrun-portal`](https://github.com/droidrun/droidrun-portal) 当前重定向到 **[Mobilerun Portal](https://github.com/droidrun/mobilerun-portal)**。它是 Android App，手机内实现无障碍树采集、截图、节点输入和手势操作，提供 HTTP/WebSocket，且支持手机主动建立 reverse WebSocket。因此可以作为 MobileAgent 的 App DeviceBridge 候选，运行时无需电脑常驻。[固定版本 README](https://github.com/droidrun/mobilerun-portal/blob/d4cb7d6657385488239812e776df584f890e32fd/README.md)、[反向连接](https://github.com/droidrun/mobilerun-portal/blob/d4cb7d6657385488239812e776df584f890e32fd/docs/reverse-connection.md)

许可证为 **AGPL-3.0-or-later**；它与 MobileAgent 根代码 MIT 是不同许可，后续开源安排按实际复用方式处理。Portal 已解决一部分采集、传输和动作实现，但没有因此自动成为自主 Agent。其手势路径目前只检查派发 boolean，没有等待完成回调，仍需接入后的动作反馈与新观察验证。[LICENSE](https://github.com/droidrun/mobilerun-portal/blob/d4cb7d6657385488239812e776df584f890e32fd/LICENSE)、[GestureController](https://github.com/droidrun/mobilerun-portal/blob/d4cb7d6657385488239812e776df584f890e32fd/app/src/main/java/com/mobilerun/portal/service/GestureController.kt#L29)

## MobileAgent v3.5 的复用边界

选用 v3.5。`mobile_use` 为单模型真机截图/动作循环；完整 Manager、Executor、ActionReflector、Notetaker 和 InfoPool 在 `android_world_v3.5`。本项目提取需要的角色逻辑，解除环境、固定尺寸及任务特例耦合，再通过统一设备接口接入 App。详见[源码与改造接口](mobileagent-jev-adaptation-evidence.md)。

| 模块 | 复用与新增 |
|---|---|
| 规划、历史、记忆 | 复用 v3.5 角色与状态；继续由生成式模型提供计划和摘要 |
| 动作选择 | 新增树规范化、完整候选和 JevSelector；保留视觉 Executor 兜底 |
| 设备观察与执行 | 新增 App 无障碍树、截图、节点动作、手势及设备桥，替换宿主 ADB / 评测环境操作 |
| 操作后核验 | 新增规则和 Jev 四态验证；信息不足时调用视觉 Reflector |
| 任务生命周期 | 新增观察版本检查、命令去重、暂停/恢复、用户接管与断线处理 |

保留原模型的生成能力，才能复用规划、摘要和视觉反思；仅替换一个模型配置不会自动获得上述新增功能。Python 编排保留为开发参考，生产编排迁入 App；用户无需部署服务器。

## 操作后通过布局树核验

每次操作后等待相关页面变化，重新采集树，将子目标、动作、预期后置条件、前后树和时间信息交给验证层。明确字段或控件状态由规则判断，语义关系可以让 Jev 判断；点击回执仅表示动作被受理，不表示业务完成。

| 结果 | 含义与后续 |
|---|---|
| `SUCCESS` | 有足够证据满足本次动作后置条件；再判断整体任务是否完成 |
| `FAILURE` | 有证据显示未达预期或进入错误状态；记录错误并恢复/重规划 |
| `PENDING` | 仍在加载或等待变化；有界等待后重新观察 |
| `UNKNOWN` | 现有信息不足；需要视觉补充或用户接管 |

若视觉 Reflector 仍需要前后两张图，应在操作前缓存本地图像，需要时才上传；操作后不能补拍前图。若希望完全按需采集截图，则单独验证“前树 + 动作 + 后图”的新分支。规划和视觉动作也可能需要图像；截图失败保留未知，不能视为成功。

## 验证顺序与评测复用

先固定原 v3.5 入口与模型建立基线，提取/裁剪后复验；再依次验证 App 设备桥、Jev 候选选择、树结果核验、按需规划和完整真机闭环。AndroidWorld/AndroidControl/MobileWorld 等按能力复用，新增节点失效、等待/未知标签及中文真机任务，具体见[阶段、评测与流程图](2026-09-22-stages-evaluation-and-flows.md)。不预设成功率、速度或成本改善幅度。

## 部署范围

项目先自用、从开始公开，不上应用商店。App 需要用户启用无障碍服务；公开代码不会改变设备权限条件。保留所用 MobileAgent、设备桥依赖及模型的来源和适用许可证；Portal 只是复用候选，是否引入尚未决定。

## 证据附录

- [AccessibilityService 与 Portal 源码证据](android-accessibility-evidence.md)
- [手机内 shell、Shizuku 与 UiAutomation](android-on-device-shell-evidence.md)
- [MobileAgent 各版本矩阵与精确改造点](mobileagent-jev-adaptation-evidence.md)
- [上轮 Jev 官方能力核查](jev-evidence.md)
