# Android 独立 App 的跨应用 UI 采集与执行能力

核验日期：2026-09-21。范围为 Android 官方接口及一个直接匹配需求的开源设备端项目；未安装 APK、运行真机或评估 Google Play 上架政策。

## 结论

**技术上可以把采集和执行放在普通 Android APK 内，由用户启用 AccessibilityService 后持续运行，不需要电脑常驻，也不需要让 APK 去执行桌面版 Android CLI。** Android 官方允许把无障碍服务放入普通 App 或独立工程；服务需在 Manifest 声明并由 `BIND_ACCESSIBILITY_SERVICE` 保护，再通过 XML 声明能力。这里的“独立”指设备 runtime 独立，模型仍可在云端。[官方开发指南](https://developer.android.com/guide/topics/ui/accessibility/views/service)。

这条路线拿到的是系统向无障碍服务暴露的 **AccessibilityNodeInfo 语义树**，不是目标 App 的布局源码、内部 View 对象、完整 DOM 或绘制树。这个区别意味着树通道有明显优势，但必须保留“观察不足”的处理路径。[AccessibilityNodeInfo](https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo)、[Compose semantics](https://developer.android.com/develop/ui/compose/accessibility/semantics)。

## 1. 必要配置与读取范围

| 配置/API | 已核实能力与边界 |
|---|---|
| `android:canRetrieveWindowContent="true"` | 服务 XML 元数据声明；读取窗口内容的必要能力，不能只在运行时设置普通 flags 代替。 |
| `rootInActiveWindow` / `getRootInActiveWindow()` | API 16 起；当前触摸窗口或输入焦点窗口的根节点，结果可能为空。 |
| `getWindows()` | API 21 起；默认显示器上可交互窗口，按层级排序，并非所有 App 的后台窗口。 |
| `FLAG_RETRIEVE_INTERACTIVE_WINDOWS` | 需同时声明读取能力；未设置时 `getWindows()` 返回空，也收不到 `TYPE_WINDOWS_CHANGED`。 |
| `FLAG_REPORT_VIEW_IDS` | API 18 起；请求资源名 `package:id/name`，默认不启用。不能保证所有节点都有资源 ID。 |
| `FLAG_INCLUDE_NOT_IMPORTANT_VIEWS` | API 16 起；包括被标记为不重要的 View；增加覆盖和噪声，不能凭空恢复未暴露的虚拟语义内容。 |

依据：[服务配置说明](https://developer.android.com/guide/topics/ui/accessibility/views/service#configure_your_accessibility_service)、[rootInActiveWindow](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#getRootInActiveWindow())、[getWindows](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#getWindows())、[flags 官方定义](https://developer.android.com/reference/android/accessibilityservice/AccessibilityServiceInfo#FLAG_RETRIEVE_INTERACTIVE_WINDOWS)。

节点可提供 text、contentDescription、className、packageName、resource ID、屏幕 bounds、父子关系、可点击/可编辑/可滚动/启用/选中等状态，以及实际支持的 `actionList`。字段可能缺失；收到的节点信息是快照，`refresh()` 返回 false 表示节点已失效。设计上应生成**本次观察的临时引用**，不能把节点 index 当跨页面稳定 ID。[节点信息与 refresh](https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo#refresh())。

## 2. 操作与截图

- 优先对目标节点调用 `performAction()`：`ACTION_CLICK`、`ACTION_LONG_CLICK`、`ACTION_SCROLL_FORWARD/BACKWARD`，以及 API 21 起的 `ACTION_SET_TEXT`。后者通过 Bundle 的 `ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE` 设置文本，属于替换，不是自动追加；仍依赖目标控件支持。[AccessibilityAction 官方定义](https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo.AccessibilityAction#ACTION_SET_TEXT)。
- API 24 起可用 `dispatchGesture()` 做坐标点击/滑动，需 `canPerformGestures`。官方明确将它列为节点点击失效时的后备；提交新手势会取消正在进行的手势。返回 true 仅说明已派发，应等待回调并重读页面验证。[dispatchGesture](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#dispatchGesture(android.accessibilityservice.GestureDescription,%20android.accessibilityservice.AccessibilityService.GestureResultCallback,%20android.os.Handler))。
- API 30 起 `takeScreenshot(displayId, ...)`，需 `canTakeScreenshot`；API 34 起 `takeScreenshotOfWindow(windowId, ...)` 可取得指定窗口内容，避免被无障碍 overlay 遮盖。它们不是绕过安全窗口的接口：存在无访问权限、无效窗口/显示器、请求间隔太短、安全内容等失败。[截图 API](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#takeScreenshot(int,%20java.util.concurrent.Executor,%20android.accessibilityservice.AccessibilityService.TakeScreenshotCallback))、[窗口截图](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#takeScreenshotOfWindow(int,%20java.util.concurrent.Executor,%20android.accessibilityservice.AccessibilityService.TakeScreenshotCallback))、[Android CTS 对截图节流的测试](https://android.googlesource.com/platform/cts/+/51392514171a4d835df9d4d698fc553444aff77d/tests/accessibilityservice/src/android/accessibilityservice/cts/AccessibilityTakeScreenshotTest.java)。

## 3. 树的覆盖边界

| UI 类型 | 可期待什么，不能假设什么 |
|---|---|
| 原生标准控件 | 通常能提供文本、状态和动作；无障碍父子关系可以省略布局容器，不等同于真实 `ViewParent`。 |
| WebView/Chrome | 可通过 `AccessibilityNodeProvider` 暴露网页的虚拟节点，不是“WebView 一律没有树”；但得到的是浏览器加工的无障碍树，不是 DOM 全量访问权限。Chromium 会按需生成和缓存节点、按服务需求过滤信息。 |
| Jetpack Compose | 内置组件提供 semantics；多个 composable 可合并为一个语义节点。`clearAndSetSemantics` 能清除或替换后代语义，`hideFromAccessibility` 能将内容隐藏。 |
| Canvas、自绘控件、游戏画面 | 只有开发者提供虚拟节点/semantics，服务才有对应结构。仅有像素绘制时，可能只能看到一个容器；增加读取 flags 不能从像素自动造出节点。 |

依据：[节点父子关系说明](https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo#setBoundsInParent(android.graphics.Rect))、[Chromium Android 无障碍实现说明](https://chromium.googlesource.com/chromium/src/+/main/docs/accessibility/browser/android.md)、[Compose semantics](https://developer.android.com/develop/ui/compose/accessibility/semantics)、[Compose 合并与清除](https://developer.android.com/develop/ui/compose/accessibility/merging-clearing)、[虚拟节点机制](https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo#addChild(android.view.View,%20int))。

## 4. 事件驱动并不自动等于稳定页面快照

服务可关注窗口、内容、滚动、文本变化事件；`notificationTimeout` 用于限制同类事件过于频繁的通知。它只是通知节流，不证明网络内容、动画或所有异步更新已结束。节点还可能在云端决策返回前失效。[事件配置](https://developer.android.com/guide/topics/ui/accessibility/views/service#register_for_accessibility_events)、[notificationTimeout](https://developer.android.com/reference/android/accessibilityservice/AccessibilityServiceInfo#notificationTimeout)、[refresh](https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo#refresh())。

以下为工程建议，而非 Android 自动提供的保证：事件只标记状态已变更；执行后等待相关事件或短超时，重新采集；为每份快照附 `observationId/windowId/packageName/timestamp`；执行前重新验证节点、动作和 bounds；连续状态无变化、树为空、候选缺失时停止重复点击并转入截图/重新规划；将动作提交结果和用户目标完成结果分开记录。

## 5. 已核实的现成设备桥：DroidRun / Mobilerun Portal

旧地址 [`droidrun/droidrun-portal`](https://github.com/droidrun/droidrun-portal) 当前重定向到 [`droidrun/mobilerun-portal`](https://github.com/droidrun/mobilerun-portal)。固定检查版本为 [`d4cb7d6657385488239812e776df584f890e32fd`](https://github.com/droidrun/mobilerun-portal/commit/d4cb7d6657385488239812e776df584f890e32fd)，提交时间 2026-08-18 17:41:09 UTC，版本更新为 0.7.25。

它是 **Android 设备端 App，不是仅靠 host `uiautomator dump` 的脚本**：用户安装并启用服务后，手机内读取树和执行操作；本地 HTTP 默认 8080、WebSocket 默认 8081，另有 ADB ContentProvider 通道。也支持手机主动向云端建立 reverse WebSocket，因此常规网络控制不要求电脑常驻。[README](https://github.com/droidrun/mobilerun-portal/blob/d4cb7d6657385488239812e776df584f890e32fd/README.md)、[本地接口文档](https://github.com/droidrun/mobilerun-portal/blob/d4cb7d6657385488239812e776df584f890e32fd/docs/local-api.md)、[反向连接文档](https://github.com/droidrun/mobilerun-portal/blob/d4cb7d6657385488239812e776df584f890e32fd/docs/reverse-connection.md)。

已检查的关键实现：

- [服务配置](https://github.com/droidrun/mobilerun-portal/blob/d4cb7d6657385488239812e776df584f890e32fd/app/src/main/res/xml/accessibility_service_config.xml) 同时声明 window content、gestures、screenshot 和上述三个 flags。
- [AccessibilityRootResolver](https://github.com/droidrun/mobilerun-portal/blob/d4cb7d6657385488239812e776df584f890e32fd/app/src/main/java/com/mobilerun/portal/core/AccessibilityRootResolver.kt#L24) 实际读取 `rootInActiveWindow` 与 `windows`，并处理根为空、层级与重复窗口。
- [AccessibilityTreeBuilder](https://github.com/droidrun/mobilerun-portal/blob/d4cb7d6657385488239812e776df584f890e32fd/app/src/main/java/com/mobilerun/portal/core/AccessibilityTreeBuilder.kt#L23) 将节点转为 JSON，包括 text、resourceId、bounds、actions，另有遍历深度、循环和可见性过滤。
- [GestureController](https://github.com/droidrun/mobilerun-portal/blob/d4cb7d6657385488239812e776df584f890e32fd/app/src/main/java/com/mobilerun/portal/service/GestureController.kt#L29) 用 `dispatchGesture` 执行点击/滑动；该实现传入 null 回调，不能把派发成功直接当作页面操作完成。
- [服务输入实现](https://github.com/droidrun/mobilerun-portal/blob/d4cb7d6657385488239812e776df584f890e32fd/app/src/main/java/com/mobilerun/portal/service/MobilerunAccessibilityService.kt#L1003) 存在 `ACTION_SET_TEXT`；[API 30 截图实现](https://github.com/droidrun/mobilerun-portal/blob/d4cb7d6657385488239812e776df584f890e32fd/app/src/main/java/com/mobilerun/portal/service/AccessibilityScreenshotApi30.kt#L15) 调用系统截图并编码 PNG。
- [构建配置](https://github.com/droidrun/mobilerun-portal/blob/d4cb7d6657385488239812e776df584f890e32fd/app/build.gradle.kts#L12) 为 minSdk 26 / targetSdk 34；[截图分支](https://github.com/droidrun/mobilerun-portal/blob/d4cb7d6657385488239812e776df584f890e32fd/app/src/main/java/com/mobilerun/portal/service/MobilerunAccessibilityService.kt#L1408) 在 API 26–29 使用 MediaProjection，API 30+ 使用无障碍截图，不能说“无障碍截图本身从 Android 8 就支持”。

**许可证为 AGPL-3.0-or-later**，不是 MIT/Apache。本项目计划从开始公开，Portal 是否引入尚未确定；应依据实际代码复用和分发方式处理其许可要求，不能只因本项目公开就推定许可兼容。[LICENSE](https://github.com/droidrun/mobilerun-portal/blob/d4cb7d6657385488239812e776df584f890e32fd/LICENSE)。

**适用性判断：** 它非常接近本需求的设备桥，可用于原型或作为自建 runtime 的实现参考；“手机内采集与执行”“无需电脑常驻”和“手机内已有完整自主 Agent”是三个不同结论。当前证据支持前两项，未证明第三项。将 Jev 接入仍需候选动作生成、云端鉴权/请求、任务状态机、文本参数生成、动作验证和失败恢复。

本次仅列一个已深入核验的项目，未追加未经核实的 Auto.js 分支列表。
