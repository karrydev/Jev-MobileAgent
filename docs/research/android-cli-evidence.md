# Android CLI 能力与手机自动操作可行性证据

调研日期：2026-09-21。范围：Google 官方 Android CLI、本机安装版本、Android 官方机制。只进行了帮助、版本、设备枚举和本机发行包字节码的只读检查；未截图、读取手机屏幕、点击、输入、安装 APK、更新 CLI 或启动模拟器。`adb devices -l` 自动启动了主机 ADB daemon，但没有连接设备。

## 结论

**Android CLI 可以成为“结构化 UI → 文本模型决策 → ADB 执行动作”的观察层。**它提供 JSON 布局、截图和截图区域编号转坐标；实际点击、滑动、输入和返回由 `adb shell input` 完成。Jev 只接收文本/JSON，应把 UI 树经过裁剪和规范化后交给它；无可访问性语义的图像、WebView、自绘 Canvas 仍需要独立视觉模型或人工接管，不能由 JSON 保证覆盖。

Google 的 CLI 以开发机为运行环境，通过 ADB 操作真机或模拟器；它不是普通手机 App 可直接拥有的跨应用自动点击权限。原型可以在 Mac 主机运行；若要脱离电脑，需另做 Android 端 AccessibilityService 等执行器，并重新处理用户授权、进程存活和平台限制。

## 本机证据

| 项目 | 实际结果 |
|---|---|
| `command -v android` | `/Users/liangkairui/.local/bin/android` |
| `file` | Mach-O 64-bit executable arm64 |
| `android --version` | `1.0.15985488` |
| `android info` | SDK `/Users/liangkairui/Library/Android/sdk`；launcher `1.0.15433482` |
| CLI 更新提示 | 可更新至 `1.0.16261425`；本次未更新 |
| `adb version` | Android Debug Bridge `1.0.41`，platform-tools `33.0.1-8253317` |
| `adb devices -l` | 列表为空 |

执行过 `android -h`、`android help`、`android layout --help`、`android screen --help`、`android screen capture --help`、`android screen resolve --help`、`android info`、`android docs search ...`。其中 capture/resolve 的帮助参数处理不完善，输出了 usage 和参数错误；没有实际运行设备操作。

Google 官方入口：[Android CLI overview](https://developer.android.com/tools/agents/android-cli)、[官方 Android skills 仓库](https://github.com/android/skills/tree/main/devtools/android-cli)、[发行说明](https://developer.android.com/tools/agents/android-cli/release-notes)。本机可执行文件与官方 CLI 的命令、版本一致。未定位并审计完整 CLI 开源源码仓库；下面内部机制证据来自已安装的官方发行包，而不是推测其实现。

## 观察与动作能力

| 能力 | 本机版本已确认的命令/机制 | 边界 |
|---|---|---|
| UI 树 | `android layout --device SERIAL --pretty` | JSON；`--output` 可写文件；本次未在设备采集 |
| 增量 UI | `android layout --diff` | **仅本机旧版确认有实际 diff 行为**，新版情况见下文 |
| 截图 | `android screen capture --device SERIAL -o screen.png` | PNG；截图需要视觉模型才能理解图片内容 |
| 区域标注 | `android screen capture --annotate -o screen.png` | 标号和边框，不等于理解图标含义 |
| 编号转坐标 | `android screen resolve --screenshot screen.png --string 'tap #3'` | 仅生成坐标字符串，不执行点击；编号绑定该截图 |
| 点击 | `adb -s SERIAL shell input tap X Y` | 采用最近一次布局的 center 或截图坐标 |
| 滑动 | `adb -s SERIAL shell input swipe X1 Y1 X2 Y2 DURATION_MS` | 页面变化后重新观察，不能长期复用坐标 |
| 返回/主页 | `adb -s SERIAL shell input keyevent KEYCODE_BACK` / `KEYCODE_HOME` | ADB input 的按键能力 |
| 文本输入 | `adb -s SERIAL shell input text 'Hello%sworld'` | 不是可靠的中文/任意 Unicode 输入接口 |
| 等待/验证 | 重新读取布局并检查条件；自行实现 timeout 和重试 | CLI 没有完整任务状态机或成功判定器 |

以上动作语法由 Google [交互参考](https://developer.android.com/agents/skills/devtools/android-cli/references/interact)和 AOSP [InputShellCommand](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/main/services/core/java/com/android/server/input/InputShellCommand.java)核实；**不是本机真机执行成功的证据**。官方交互文档个别示例写 `--screen`，但本机 help 与 overview 写 `--screenshot`，应以后者和实际版本探测为准。

## 布局原理、适用范围和字段

本机发行包路径：

```text
/Users/liangkairui/.android/cli/bundles/dfb6e68183ae8e0c4e1218a5cef49d6bda605fda/main.jar
```

使用 `/usr/bin/javap -c -p -classpath <上述 main.jar> <类名>` 检查，关键类为 `com.android.cli.ui.commands.Layout`、`com.android.cli.interact.commands.LayoutCommand`、`com.android.cli.interact.layout.UIElement`、`com.android.cli.interact.layout.ElementSerializer$Companion`、`com.android.cli.interact.layout.Key`。

本机默认路径是：

```text
Layout → LayoutCommand → AdbConnectedDevice.shellCommandAsText
       → uiautomator dump /sdcard/window_dump.xml
       → 读取 XML → UIElement → JSON
```

因此，它不是依赖应用调试连接的 Layout Inspector，也不要求目标 App 加入 SDK、暴露源代码或声明 debuggable。依据 UI Automator 的官方跨应用定位，它可以观察系统界面和已安装 App 暴露的前台 UI；“可以跨应用”不代表“任何 App 的每个可见像素都有语义节点”。[UI Automator 官方文档](https://developer.android.com/training/testing/other-components/ui-automator-legacy)

官方交互参考列出的可选信息包括文本、资源 ID、content description、交互能力、状态、屏幕 bounds/center、屏外标志。**本机旧版序列化器与该网页字段名不完全相同**：

| 本机序列化字段 | 已确认的含义/限制 |
|---|---|
| `text` | 非空元素文字 |
| `content-desc` | 非空可访问性描述；网页写作 `contentDesc` |
| `resource-id` | 非空资源标识，序列化前剥离 `:id/` 以前前缀；网页写作 `resourceId` |
| `interactions` | 可包含 checkable/clickable/focusable/scrollable/long-clickable/password |
| `state` | checked/focused/selected 等状态 |
| `center` | 有有效 bounds 时提供中心坐标 |
| `bounds` | 本机该序列化路径仅在 scrollable 元素输出 |
| `off-screen` | 本机依据 bounds 面积判断；不要视为完整遮挡检测结果 |
| `key` | 从父路径、resource ID、必要时 sibling index 构造，再取 hash；不是稳定远程对象句柄 |

模型输出应引用我们自己的 `snapshot_id + element_id`，执行器从当前快照查出坐标。布局刷新、滚动和列表复用后旧 key/坐标可能指向错误内容；不能把 key 当作跨页面永久 ID。对字段名、顶层数组/树形结构、缺失值和版本进行归一化，保留包名/窗口信息时应另采集或从未裁剪来源保留，不能假定简化 JSON 自带这些信息。

## 版本漂移会影响工程方案

官方 2026-09 发行说明：`1.0.16251017` 增加 `--full` 和 `--no-idle`；默认只返回交互节点；`1.0.16261425` 修复 layout，并允许 `ANDROID_CLI_LAYOUT_V1=1` 回退旧实现。[发行说明](https://developer.android.com/tools/agents/android-cli/release-notes)

官方更新于 2026-09-16 的 [CLI skill help](https://developer.android.com/agents/skills/devtools/android-cli/skill)还列出 `--flat`，且将 `--diff` 标记为 deprecated/no-op；更新于 2026-09-11 的交互参考仍描述旧 diff。不要用旧文档推导新版本行为，更不能保证 `--diff` 永久节省上下文。

同一本机发行包还包含 `LayoutV2Command` 与 `InstrumentationServerConnector`；字节码显示 V2 经 ADB 启动 `com.android.cli.interact.instrumentation/.InstrumentationServer`，必要时安装内置 instrumentation APK 并转发端口。这只是发行包中的 V2 路径证据；本次没有启用或执行它，也未审计最新版本的完整内部行为。升级后先探测 schema 与设备端依赖，再固定版本验收。

## 覆盖面与实际限制

- **Native Views / 普通表单**：通常最适合树驱动，UI Automator 有跨应用能力；自定义 View 需要 App 正确提供 accessibility 信息。[UI Automator](https://developer.android.com/training/testing/other-components/ui-automator-legacy)
- **Compose**：foundation/material 通常生成 semantics；但树与视觉/Composition 树不同，存在合并、裁剪和缺失。自绘 Canvas 日历可能只暴露整个日历，具体日期不可定位。不能承诺拿到每个 composable。[Compose semantics](https://developer.android.com/develop/ui/compose/accessibility/semantics)
- **WebView / 动画 / 图片图标**：Google 明确提示 layout 可能失败或漏内容，建议截图回退。对不接收图像的模型，截图需要先交给视觉组件转成描述/目标坐标。[交互参考](https://developer.android.com/agents/skills/devtools/android-cli/references/interact)
- **中文输入**：AOSP `input text` 将字符映射成虚拟键盘 KeyEvent；映射可能失败，官方 KeyCharacterMap 文档也不把它视为可靠文本输入方案。原型应增加 Unicode 输入适配器，例如 UI Automator `setText`、Accessibility `ACTION_SET_TEXT` 或经用户启用的输入法桥接，并实测目标输入框。[AOSP 实现](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/main/services/core/java/com/android/server/input/InputShellCommand.java)、[KeyCharacterMap](https://developer.android.com/reference/android/view/KeyCharacterMap#getEvents(char[]))、[UI Automator setText](https://developer.android.com/training/testing/other-components/ui-automator-legacy)
- **截图也有边界**：窗口使用 `FLAG_SECURE` 可以阻止其内容出现在截图里，视觉回退不能解除该边界。[WindowManager.LayoutParams](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#FLAG_SECURE)

## 权限、主机与端侧边界

Google 发布的是 Linux x86_64、macOS arm64/x86_64、Windows 主机安装包；官方未在这组安装说明中提供 Android/Termux 发行包。[官方安装说明](https://developer.android.com/agents/skills/devtools/android-cli/skill)

真机通过 USB 调试和电脑 RSA 授权连接；无线 ADB 需要无线调试/配对。这条原型路线通常不要求 root，但需要用户允许调试。不能将它宣传成普通消费者安装一个没有特殊权限的 APK 就能控制全部 App。[ADB 官方文档](https://developer.android.com/tools/adb)

独立手机端可以考虑用户启用的 AccessibilityService：读取窗口内容需要声明相应 capability；手势需要 `canPerformGestures`，动作可通过节点 action 或 `dispatchGesture` 执行。这是重新实现端侧观察/执行服务，不是把 Mac CLI 直接搬进普通 App。[AccessibilityService](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService)

## 对 Jev + UI 树方案的工程判断

以下是本次基于能力边界的设计推论，尚无真机成功率或时延数据：

1. **用于开发与对照**：Android CLI 可以提供主机侧树与 ADB 对照；本项目的生产路径为 App 无障碍采集 → 规范化/裁剪 → Jev 选择 → App 执行 → 新树核验，CLI 不是 APK 的运行依赖。
2. **应有视觉升级路径**：节点不足、候选歧义、连续无进展时，交给支持图像的模型；同一执行器执行其坐标结果。
3. **执行器不能只是模型输出 shell 就运行**：用固定 action schema，校验 element 是否属于当前 snapshot、动作是否合法、输入框焦点、超时和重试上限。付费、发布、删除等提交动作设独立确认点。
4. **延迟未实测**：应分别记录 layout/截图耗时、模型耗时、执行耗时、页面稳定耗时。减少图片传输可能降低成本与部分延迟，但 ADB shell 启动、UI idle 等待和页面网络加载仍存在；不能根据模型低延迟推导端到端低延迟。
5. **验收应按页面类型分层**：原生表单、Compose 列表、WebView、Canvas/图标、中文输入分别测；对比纯树、纯截图和树优先+视觉回退，统计任务成功率/错误动作率/恢复率/每任务 tokens/时延。

本次环境无已连接设备，因此尚不能判断用户目标 App 的真实 UI 树完整度，也没有进行任何动作成功率测试。
